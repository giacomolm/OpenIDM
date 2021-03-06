/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2015 ForgeRock AS.
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

/*global require */

require.config({
    baseUrl: '../www',
    paths: {
        jquery: "libs/jquery-2.1.1-min",
        doTimeout: "libs/jquery.ba-dotimeout-1.0-min",
        underscore: "libs/lodash-2.4.1-min",
        sinon: "libs/sinon-1.15.4"
    },
    shim: {
        underscore: {
            exports: "_"
        },
        sinon: {
            exports: "sinon"
        },
        doTimeout: {
            deps: ["jquery"],
            exports: "doTimeout"
        }
    }
});

require([
    "../test/tests/mocks/systemInit",
    "jquery",
    "underscore",
    "sinon"
], function (systemInit, $, _, sinon) {

    sinon.FakeXMLHttpRequest.useFilters = true;
    sinon.FakeXMLHttpRequest.addFilter(function (method, url, async, username, password) {
        return /((\.html)|(\.css)|(\.less)|(\.json))$/.test(url);
    });

    var server = sinon.fakeServer.create();
    server.autoRespond = true;
    systemInit(server);

    $("head", document).append("<base href='../www/' />");

    require(['main','../test/run'], function (appMain, run) {
        run(server);
    });

});
