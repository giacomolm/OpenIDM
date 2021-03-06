/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014 ForgeRock AS. All Rights Reserved
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

/*global exports, openidm */

(function () {

    var canBeEncrypted= function (value) {
        // we are not going to do anything if the value is undefined/null/already encrypted
        return typeof value !== 'undefined' && value !== null && !openidm.isEncrypted(value);
    };

    exports.encrypt = function (value, cipher, alias) {

        cipher = cipher || 'AES/CBC/PKCS5Padding';
        alias = alias || identityServer.getProperty('openidm.config.crypto.alias', 'true', true);

        if (!canBeEncrypted(value)) {
            return value;
        }

        return openidm.encrypt(value, cipher, alias);
    };

    exports.hash = function (value, algorithm) {
        algorithm = algorithm || "SHA-256";
        if (!canBeEncrypted(value) || typeof value !== "string") {
            return value;
        }
        return openidm.hash(value, algorithm);
    };

}());
