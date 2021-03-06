/**
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */

/*global define */

define("org/forgerock/openidm/ui/admin/selfservice/PasswordResetConfigView", [
    "jquery",
    "org/forgerock/openidm/ui/admin/selfservice/AbstractSelfServiceView"
], function($, AbstractSelfServiceView) {
    var PasswordResetConfigView = AbstractSelfServiceView.extend({
        template: "templates/admin/selfservice/PasswordResetConfigTemplate.html",
        events: {
            "change .all-check" : "controlAllSwitch",
            "change .section-check" : "controlSectionSwitch",
            "click .save-config" : "saveConfig",
            "click .wide-card.active" : "showDetailDialog",
            "click li.disabled a" : "preventTab"
        },
        partials: [
            "partials/selfservice/password/_userIdValidation.html",
            "partials/selfservice/password/_resetStage.html",
            "partials/selfservice/_advancedoptions.html",
            "partials/selfservice/_selfserviceblock.html",
            "partials/form/_basicInput.html"
        ],
        data: {
            hideAdvanced: true,
            config: {},
            configList: []
        },
        model: {
            surpressSave: false,
            uiConfigurationParameter: "passwordReset",
            serviceType: "password",
            configUrl: "selfservice/reset",
            msgType: "selfServicePassword",
            "configDefault": {
                "stageConfigs": [
                    {
                        "name" : "userIdValidation",
                        "queryFields" : [
                            "userName",
                            "mail"
                        ],
                        "identityIdField" : "_id",
                        "identityEmailField" : "mail",
                        "identityServiceUrl" : "managed/user",
                        "email" : {
                            "from": "info@admin.org",
                            "subject": "Reset password email",
                            "mimeType" : "text/html",
                            "message": "<h3>This is your reset email.</h3><h4><a href=\"%link%\">Email verification link</a></h4>",
                            "verificationLinkToken": "%link%",
                            "verificationLink": "https://localhost:8443/#passwordReset/"
                        }
                    },
                    {
                      "name" : "kbaSecurityAnswerVerificationStage",
                      "kbaPropertyName" : "kbaInfo",
                      "identityServiceUrl" : "managed/user",
                      "numberOfQuestionsUserMustAnswer" : "1",
                      "kbaConfig" : null
                    },
                    {
                        "name" : "resetStage",
                        "identityServiceUrl" : "managed/user",
                        "identityPasswordField" : "password"
                    }
                ],
                "snapshotToken" : {
                    "type": "jwt",
                    "sharedKey" : "!tHiSsOmEsHaReDkEy!",
                    "keyPairAlgorithm" : "RSA",
                    "keyPairSize" : 1024,
                    "jweAlgorithm" : "RSAES_PKCS1_V1_5",
                    "encryptionMethod" : "A128CBC_HS256",
                    "jwsAlgorithm" : "HS256",
                    "tokenExpiry": 1800
                },
                "storage": "stateless"
            },
            "saveConfig": {}
        },
        render: function(args, callback) {
            this.data.configList = [{
                type: "userIdValidation",
                title: $.t("templates.selfservice.emailValidation"),
                help: $.t("templates.selfservice.emailValidationDescription")
            },
            {
                type: "kbaSecurityAnswerVerificationStage",
                title: $.t("templates.selfservice.kbaSecurityAnswerVerificationStageForm"),
                help: $.t("templates.selfservice.kbaSecurityAnswerVerificationStageFormDescription")
            },
            {
                type: "resetStage",
                title: $.t("templates.selfservice.passwordResetForm"),
                help: $.t("templates.selfservice.passwordResetFormDescription")
            }];

            this.selfServiceRender(args, callback);
        }
    });

    return new PasswordResetConfigView();
});
