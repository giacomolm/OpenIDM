/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014 ForgeRock AS. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

/*global define */

define("org/forgerock/openidm/ui/admin/delegates/SchedulerDelegate", [
    "org/forgerock/commons/ui/common/util/Constants",
    "org/forgerock/commons/ui/common/main/AbstractDelegate"
], function(constants, AbstractDelegate) {

    var obj = new AbstractDelegate(constants.host + "/openidm/scheduler");

    obj.availableSchedules = function() {
        return obj.serviceCall({
            url: "?_queryId=query-all-ids",
            type: "GET"
        });
    };

    obj.specificSchedule = function(scheduleId) {
        return obj.serviceCall({
            url: "/" + scheduleId,
            type: "GET"
        });
    };

    obj.saveSchedule = function(scheduleId, schedule) {
        return obj.serviceCall({
            url: "/" + scheduleId,
            type: "PUT",
            data: JSON.stringify(schedule)
        });
    };

    obj.deleteSchedule = function(scheduleId) {
        return obj.serviceCall({
            url: "/" + scheduleId,
            type: "DELETE"
        });
    };

    obj.addSchedule = function(schedule) {
        return obj.serviceCall({
            url: "?_action=create",
            type: "POST",
            data: JSON.stringify(schedule)
        });
    };

    return obj;
});
