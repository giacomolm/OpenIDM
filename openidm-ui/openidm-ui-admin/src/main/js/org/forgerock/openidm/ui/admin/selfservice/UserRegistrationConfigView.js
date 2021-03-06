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

define("org/forgerock/openidm/ui/admin/selfservice/UserRegistrationConfigView", [
    "jquery",
    "org/forgerock/openidm/ui/admin/selfservice/AbstractSelfServiceView"
], function($, AbstractSelfServiceView) {
    var UserRegistrationConfigView = AbstractSelfServiceView.extend({
        template: "templates/admin/selfservice/UserRegistrationConfigTemplate.html",
        events: {
            "change .all-check" : "controlAllSwitch",
            "change .section-check" : "controlSectionSwitch",
            "click .save-config" : "saveConfig",
            "click .wide-card.active" : "showDetailDialog",
            "click li.disabled a" : "preventTab"
        },
        partials: [
            "partials/selfservice/user/_emailValidation.html",
            "partials/selfservice/user/_kbaStage.html",
            "partials/selfservice/user/_selfRegistration.html",
            "partials/selfservice/user/_userDetails.html",
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
            uiConfigurationParameter: "selfRegistration",
            serviceType: "user",
            configUrl: "selfservice/registration",
            msgType: "selfServiceUserRegistration",
            "configDefault": {
                "stageConfigs" : [
                    {
                        "name" : "emailValidation",
                        "email" : {
                            "from" : "info@admin.org",
                            "subject" : "Register new account",
                            "mimeType" : "text/html",
                            "message" : "<h3>This is your registration email.</h3><h4><a href=\"%link%\">Email verification link</a></h4>",
                            "verificationLinkToken" : "%link%",
                            "verificationLink" : "https://localhost:8443/#register/"
                        }
                    },
                    {
                        "name" : "userDetails",
                        "identityEmailField" : "mail"
                    },
                    {
                        "name" : "kbaSecurityAnswerDefinitionStage",
                        "kbaPropertyName" : "kbaInfo",
                        "kbaConfig" : null
                    },
                    {
                        "name" : "selfRegistration",
                        "identityServiceUrl" : "managed/user"
                    }
                ],
                "snapshotToken" : {
                    "type" : "jwt",
                    "sharedKey" : "!tHiSsOmEsHaReDkEy!",
                    "keyPairAlgorithm" : "RSA",
                    "keyPairSize" : 1024,
                    "jweAlgorithm" : "RSAES_PKCS1_V1_5",
                    "encryptionMethod" : "A128CBC_HS256",
                    "jwsAlgorithm" : "HS256",
                    "tokenExpiry" : 1800
                },
                "storage" : "stateless"
            },
            "saveConfig": {}
        },
        render: function(args, callback) {
            this.data.configList = [{
                type: "emailValidation",
                title: $.t("templates.selfservice.emailValidation"),
                help: $.t("templates.selfservice.emailValidationDescription")
            },
            {
                type: "userDetails",
                title: $.t("templates.selfservice.userDetailsTitle"),
                help: $.t("templates.selfservice.userDetailsHelp")
            },
            {
                type: "kbaSecurityAnswerDefinitionStage",
                title: $.t("templates.selfservice.kbaSecurityAnswerDefinitionStageTitle"),
                help: $.t("templates.selfservice.kbaSecurityAnswerDefinitionStageHelp")
            },
            {
                type: "selfRegistration",
                title: $.t("templates.selfservice.registrationForm"),
                help: $.t("templates.selfservice.registrationFormDescription")
            }];

            this.selfServiceRender(args, callback);
        }
    });

    return new UserRegistrationConfigView();
});
