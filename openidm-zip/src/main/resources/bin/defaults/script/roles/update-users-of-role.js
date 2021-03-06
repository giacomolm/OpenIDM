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

/*global resourceName */

// Don't bother querying for members if the resourceName is simply "managed/role", because
// that would only be the case when this is a new role being created with a server-assigned _id;
// in that case, there will never be existing members.
if (!resourceName.toString().equals("managed/role")) {
    var users = openidm.query(resourceName.toString() +'/members', {'_queryFilter': 'true'}, ['*']).result;

    for (var i = 0; i < users.length; i++) {
        var user = users[i];
        logger.debug("Trigger sync check for user: {}", user);
        openidm.action("managed/user/" + user._id, "triggerSyncCheck", {}, {});
    }
}
