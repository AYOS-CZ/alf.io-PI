/*
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */

package alfio.pi.manager

import alfio.pi.ConnectionDescriptor
import alfio.pi.model.*
import alfio.pi.model.CheckInStatus.*
import alfio.pi.repository.*
import alfio.pi.wrapper.CannotBeginTransaction
import alfio.pi.wrapper.doInTransaction
import alfio.pi.wrapper.tryOrDefault
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.print.DocFlavor
import javax.print.SimpleDoc

private val logger = LoggerFactory.getLogger("CheckInManager")!!

private val eventAttendeesCache: ConcurrentMap<String, Map<String, String>> = ConcurrentHashMap()

@Component
open class CheckInDataManager(@Qualifier("masterConnectionConfiguration") val master: ConnectionDescriptor,
                              val scanLogRepository: ScanLogRepository,
                              val eventRepository: EventRepository,
                              val userPrinterRepository: UserPrinterRepository,
                              val userRepository: UserRepository,
                              val transactionManager: PlatformTransactionManager,
                              val printerRepository: PrinterRepository,
                              val gson: Gson,
                              val labelTemplates: List<LabelTemplate>) {
    private val ticketDataNotFound = "ticket-not-found"

    private fun getLocalTicketData(event: Event, uuid: String, hmac: String) : CheckInResponse {
        val eventData = eventAttendeesCache.computeIfAbsent(event.key, {loadCachedAttendees(it)})
        val key = calcHash256(hmac)
        val result = eventData[key]
        return tryOrDefault<CheckInResponse>().invoke({
            if(result != null && result !== ticketDataNotFound) {
                val ticketData = gson.fromJson(decrypt("$uuid/$hmac", result), TicketData::class.java)
                TicketAndCheckInResult(Ticket(uuid, ticketData.firstName, ticketData.lastName, ticketData.email, ticketData.company), CheckInResult(ticketData.checkInStatus))
            } else {
                logger.warn("no eventData found for $key. Cache size: ${eventData.size}")
                EmptyTicketResult()
            }
        }, {
            logger.warn("got Exception while loading/decrypting local data", it)
            EmptyTicketResult()
        })
    }

    internal fun performCheckIn(eventName: String, uuid: String, hmac: String, username: String) : CheckInResponse = doInTransaction<CheckInResponse>()
        .invoke(transactionManager, { doPerformCheckIn(eventName, hmac, username, uuid) }, {
            if(it !is CannotBeginTransaction) {
                logger.error("error during check-in", it)
            }
            EmptyTicketResult()
        })

    private fun doPerformCheckIn(eventName: String, hmac: String, username: String, uuid: String): CheckInResponse {
        return eventRepository.loadSingle(eventName)
            .flatMap { event -> userRepository.findByUsername(username).map { user -> event to user } }
            .map { eventUser ->
                val event = eventUser.first
                val eventId = event.id
                val user = eventUser.second
                scanLogRepository.loadSuccessfulScanForTicket(eventId, uuid)
                    .map(fun(existing: ScanLog) : CheckInResponse = DuplicateScanResult(originalScanLog = existing))
                    .orElseGet {
                        val localDataResult = getLocalTicketData(event, uuid, hmac)
                        if (localDataResult.isSuccessful()) {
                            localDataResult as TicketAndCheckInResult
                            val remoteResult = remoteCheckIn(event.key, uuid, hmac)
                            val localResult = if(arrayOf(ALREADY_CHECK_IN, MUST_PAY, INVALID_TICKET_STATE).contains(remoteResult.result.status)) {
                                remoteResult.result.status
                            } else {
                                CheckInStatus.SUCCESS
                            }
                            val ticket = localDataResult.ticket!!
                            val labelPrinted = remoteResult.isSuccessful() && labelTemplates.isNotEmpty() && printLabel(user, eventId, ticket)
                            scanLogRepository.insert(eventId, uuid, user.id, localResult, remoteResult.result.status, labelPrinted)
                            logger.info("returning status $localResult for ticket $uuid (${ticket.fullName})")
                            TicketAndCheckInResult(ticket, CheckInResult(localResult))
                        } else {
                            localDataResult
                        }
                    }
            }.orElseGet{ EmptyTicketResult() }
    }

    private fun printLabel(user: User, eventId: Int, ticket: Ticket): Boolean {
        return tryOrDefault<Boolean>().invoke({
            userPrinterRepository.getOptionalUserPrinter(user.id, eventId)
                .map { printerRepository.findById(it.printerId) }
                .map { printer ->
                    val pdf = generatePDFLabel(ticket.firstName, ticket.lastName, ticket.company.orEmpty(), ticket.uuid).invoke(labelTemplates.first())
                    val printJob = findPrinterByName(printer.name)?.createPrintJob()
                    if(printJob == null) {
                        logger.warn("cannot find printer with name ${printer.name}")
                        false
                    } else {
                        printJob.print(SimpleDoc(pdf, DocFlavor.BYTE_ARRAY.PDF, null), null)
                        true
                    }
                }.orElse(false)

        }, {
            logger.error("cannot print label for ticket ${ticket.uuid}, username ${user.username}", it)
            false
        })

    }

    internal fun loadCachedAttendees(eventName: String) : Map<String, String> {
        val url = "${master.url}/admin/api/check-in/$eventName/offline"
        return tryOrDefault<Map<String, String>>().invoke({
            val request = Request.Builder()
                .addHeader("Authorization", Credentials.basic(master.username, master.password))
                .url(url)
                .build()
            val resp = httpClient.newCall(request).execute()
            if(resp.isSuccessful) {
                resp.body().use(fun(it: ResponseBody) : Map<String, String> {
                    return gson.fromJson(it.string(), object : TypeToken<Map<String, String>>() {}.type)
                }).withDefault { ticketDataNotFound }
            } else {
                logger.warn("Cannot call remote URL $url. Response Code is ${resp.code()}")
                mapOf()
            }
        }, {
            logger.warn("Got exception while trying to load the attendees", it)
            mapOf()
        })
    }

    private fun remoteCheckIn(eventKey: String, uuid: String, hmac: String) : CheckInResponse = tryOrDefault<CheckInResponse>().invoke({
        val requestBody = RequestBody.create(MediaType.parse("application/json"), gson.toJson(hashMapOf("code" to "$uuid/$hmac")))
        val request = Request.Builder()
            .addHeader("Authorization", Credentials.basic(master.username, master.password))
            .post(requestBody)
            .url("${master.url}/admin/api/check-in/event/$eventKey/ticket/$uuid")
            .build()
        val resp = httpClientWithCustomTimeout(500L, TimeUnit.MILLISECONDS).newCall(request).execute()
        if(resp.isSuccessful) {
            resp.body().use { gson.fromJson(it.string(), TicketAndCheckInResult::class.java) }
        } else {
            EmptyTicketResult(CheckInResult(CheckInStatus.RETRY))
        }
    }, {
        logger.warn("got Exception while performing remote check-in")
        EmptyTicketResult(CheckInResult(CheckInStatus.RETRY))
    })
}

fun checkIn(eventName: String, uuid: String, hmac: String, username: String) : (CheckInDataManager) -> CheckInResponse = { manager ->
    manager.performCheckIn(eventName, uuid, hmac, username)
}

private fun decrypt(key: String, payload: String): String {
    try {
        val cipherAndSecret = getCypher(key)
        val cipher = cipherAndSecret.first
        val split = payload.split(Pattern.quote("|").toRegex()).dropLastWhile(String::isEmpty).toTypedArray()
        val iv = Base64.getUrlDecoder().decode(split[0])
        val body = Base64.getUrlDecoder().decode(split[1])
        cipher.init(Cipher.DECRYPT_MODE, cipherAndSecret.second, IvParameterSpec(iv))
        val decrypted = cipher.doFinal(body)
        return String(decrypted, StandardCharsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        throw IllegalStateException(e)
    }
}

private fun getCypher(key: String): Pair<Cipher, SecretKeySpec> {
    try {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val iterations = 1000
        val keyLength = 256
        val spec = PBEKeySpec(key.toCharArray(), key.toByteArray(StandardCharsets.UTF_8), iterations, keyLength)
        val secretKey = factory.generateSecret(spec)
        val secret = SecretKeySpec(secretKey.encoded, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        return cipher to secret
    } catch (e: GeneralSecurityException) {
        throw IllegalStateException(e)
    }

}

internal fun calcHash256(hmac: String) : String {
    return MessageDigest.getInstance("SHA-256")
        .digest(hmac.toByteArray()).joinToString(separator = "", transform = {
            val result = Integer.toHexString(0xff and it.toInt())
            if(result.length == 1) {
                "0" + result
            } else {
                result
            }
        })
}

@Component
open class CheckInDataSynchronizer(val checkInDataManager: CheckInDataManager) {
    @Scheduled(fixedDelay = 5000L, initialDelay = 5000L)
    open fun performSync() {
        logger.debug("downloading attendees data")
        eventAttendeesCache.entries
            .map { it to checkInDataManager.loadCachedAttendees(it.key) }
            .filter { it.second.isNotEmpty() }
            .forEach {
                val result = eventAttendeesCache.replace(it.first.key, it.first.value, it.second)
                logger.debug("tried to replace value for ${it.first.key}, result: $result")
            }
    }

    open fun onDemandSync(events: List<RemoteEvent>) {
        logger.debug("on-demand synchronization")
        events.map { it.key!! to eventAttendeesCache[it.key] }
            .map { Triple(it.first, it.second, checkInDataManager.loadCachedAttendees(it.first)) }
            .filter { it.third.isNotEmpty() }
            .forEach {
                val result = eventAttendeesCache.replace(it.first, it.second, it.third)
                logger.debug("tried to replace value for ${it.first}, result: $result")
            }
    }
}