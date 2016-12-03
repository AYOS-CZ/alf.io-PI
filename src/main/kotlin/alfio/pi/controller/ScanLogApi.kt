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

package alfio.pi.controller

import alfio.pi.manager.findAllEntries
import alfio.pi.manager.findAllEntriesForEvent
import alfio.pi.model.ScanLog
import alfio.pi.repository.ScanLogRepository
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/scan-log")
open class ScanLogApi (val scanLogRepository: ScanLogRepository) {

    @RequestMapping("/")
    open fun loadAll() : List<ScanLog> = findAllEntries().invoke(scanLogRepository)

    @RequestMapping("/event/{eventId}")
    open fun loadForEvent(@PathVariable("eventId") eventId: Int) : List<ScanLog> = findAllEntriesForEvent(eventId).invoke(scanLogRepository)
}

