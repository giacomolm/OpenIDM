/*
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
 * Copyright 2013-2014 ForgeRock Inc.
 */

package org.forgerock.openidm.jaspi.modules;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.openidm.core.IdentityServer;
import org.forgerock.openidm.jaspi.config.OSGiAuthnFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.MessagePolicy;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * Authentication Module for authenticating users against a managed users table.
 *
 * @author Phill Cunnington
 */
public abstract class IDMUserAuthModule extends IDMServerAuthModule {

    private final static Logger logger = LoggerFactory.getLogger(IDMUserAuthModule.class);

    private final String queryId;
    private final String queryOnResource;

    // A list of ports that allow authentication purely based on client certificates (SSL mutual auth)
    private Set<Integer> clientAuthOnly = new HashSet<Integer>();

    private AuthHelper authHelper;

    /**
     * Constructor used by the commons Authentication Filter framework to create an instance of this authentication
     * module.
     */
    public IDMUserAuthModule(String queryId, String queryOnResource) {
        this.queryId = queryId;
        this.queryOnResource = queryOnResource;
    }

    /**
     * Constructor used by tests to inject dependencies.
     *
     * @param authHelper A mock of an AuthHelper instance.
     */
    public IDMUserAuthModule(AuthHelper authHelper, String queryId, String queryOnResource) {
        this.authHelper = authHelper;
        this.queryId = queryId;
        this.queryOnResource = queryOnResource;
    }

    /**
     * Initialises the ManagedUserAuthModule.
     *
     * @param requestPolicy {@inheritDoc}
     * @param responsePolicy {@inheritDoc}
     * @param handler {@inheritDoc}
     * @param options {@inheritDoc}
     */
    @Override
    protected void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler handler,
            JsonValue options) {

        String clientAuthOnlyStr = IdentityServer.getInstance().getProperty("openidm.auth.clientauthonlyports");
        if (clientAuthOnlyStr != null) {
            String[] split = clientAuthOnlyStr.split(",");
            for (String entry : split) {
                clientAuthOnly.add(Integer.valueOf(entry));
            }
        }
        logger.info("Authentication disabled on ports: {}", clientAuthOnly);

        JsonValue properties = options.get("propertyMapping");
        String userIdProperty = properties.get("userId").asString();
        String userCredentialProperty = properties.get("userCredential").asString();
        String userRolesProperty = properties.get("userRoles").asString();
        List<String> defaultRoles = options.get("defaultUserRoles").asList(String.class);

        try {
            authHelper = new AuthHelper(
                    OSGiAuthnFilterBuilder.getCryptoService(),
                    OSGiAuthnFilterBuilder.getConnectionFactory(),
                    OSGiAuthnFilterBuilder.getRouter().createServerContext(),
                    userIdProperty, userCredentialProperty, userRolesProperty, defaultRoles);
        } catch (ResourceException e) {
            logger.debug(e.getMessage(), e);
        }
    }

    /**
     * Validates the request by authenticating against either the client certificate in the request, internally or
     * Basic Authentication from the request header internally.
     *
     * @param messageInfo {@inheritDoc}
     * @param clientSubject {@inheritDoc}
     * @param serviceSubject {@inheritDoc}
     * @param securityContextMapper {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    protected AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject,
            SecurityContextMapper securityContextMapper) {

        HttpServletRequest req = (HttpServletRequest) messageInfo.getRequestMessage();
        boolean authenticated;

        final String headerLogin = req.getHeader(HEADER_USERNAME);
        String basicAuth = req.getHeader("Authorization");
        // if we see the certificate port this request is for client auth only
        if (allowClientCertOnly(req)) {
            authenticated = authenticateUsingClientCert(req, securityContextMapper);
            //Auth success will be logged in IDMServerAuthModule super type.
        } else if (headerLogin != null) {
            authenticated = authenticateUser(req, securityContextMapper);
            //Auth success will be logged in IDMServerAuthModule super type.
        } else if (basicAuth != null) {
            authenticated = authenticateUsingBasicAuth(basicAuth, securityContextMapper);
            //Auth success will be logged in IDMServerAuthModule super type.
        } else {
            //Auth failure will be logged in IDMServerAuthModule super type.
            return AuthStatus.SEND_FAILURE;
        }
        securityContextMapper.setResource(queryOnResource);
        
        final String authcid = securityContextMapper.getAuthcid();
        if (authenticated) {
            clientSubject.getPrincipals().add(new Principal() {
                public String getName() {
                    return authcid;
                }
            });
        }

        return authenticated ? AuthStatus.SUCCESS : AuthStatus.SEND_FAILURE;
    }

    /**
     * Whether to allow authentication purely based on client certificates.
     *
     * Note that the checking of the certificates MUST be done by setting jetty up for client auth required.
     *
     * @return true if authentication via client certificate only is sufficient.
     */
    private boolean allowClientCertOnly(ServletRequest request) {
        return clientAuthOnly.contains(Integer.valueOf(request.getLocalPort()));
    }

    /**
     * Authenticates the request using the client certificate from the request.
     *
     * @param request The ServletRequest.
     */
    // This is currently Jetty specific
    private boolean authenticateUsingClientCert(ServletRequest request, SecurityContextMapper securityContextMapper) {

        logger.debug("Client certificate authentication request");
        X509Certificate[] certs = getClientCerts(request);

        if (certs != null) {
            Principal existingPrincipal = request instanceof HttpServletRequest ?
                    ((HttpServletRequest) request).getUserPrincipal() : null;
            logger.debug("Request {} existing Principal {} has {} certificates", request, existingPrincipal,
                    certs.length);
            for (X509Certificate cert : certs) {
                logger.debug("Request {} client certificate subject DN: {}", request, cert.getSubjectDN());
            }
        }
        String username = certs[0].getSubjectDN().getName();
        if (certs == null || certs.length < 1 || certs[0] != null) {
            return false;
        }
        List<String> roles = new ArrayList<String>(1);
        roles.add("openidm-cert");
        securityContextMapper.setRoles(roles);
        securityContextMapper.setUsername(username);
        securityContextMapper.setUserId(username);
        logger.debug("Authentication client certificate subject {}", username);
        return true;
    }

    /**
     * Gets the client certificates from the request.
     *
     * @param request The ServletRequest.
     * @return An array of X509Certificates.
     */
    // This is currently Jetty specific
    private X509Certificate[] getClientCerts(ServletRequest request) {

        Object checkCerts = request.getAttribute("javax.servlet.request.X509Certificate");
        if (checkCerts instanceof X509Certificate[]) {
            return (X509Certificate[]) checkCerts;
        } else {
            logger.warn("Unknown certificate type retrieved {}", checkCerts);
            return null;
        }
    }

    /**
     * Authenticates the request.
     *
     * @param request The HttpServletRequest.
     */
    private boolean authenticateUser(HttpServletRequest request, SecurityContextMapper securityContextMapper) {

        logger.debug("No session, authenticating user");
        String username = request.getHeader(HEADER_USERNAME);
        String password = request.getHeader(HEADER_PASSWORD);
        if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password)) {
            logger.debug("Failed authentication, missing or empty headers");
            return false;
        }
        securityContextMapper.setUsername(username);
        return authHelper.authenticate(queryId, queryOnResource, username, password, securityContextMapper);
    }

    /**
     * Authenticates the request using the contents of the Http Authorization Header.
     *
     * @param authorizationHeader The Http Authorization Header value.
     */
    private boolean authenticateUsingBasicAuth(String authorizationHeader, SecurityContextMapper securityContextMapper) {
        logger.debug("HTTP basic authentication request");
        StringTokenizer st = new StringTokenizer(authorizationHeader);
        String isBasic = st.nextToken();
        if (isBasic == null || !isBasic.equalsIgnoreCase("Basic")) {
            return false;
        }
        String creds = st.nextToken();
        if (creds == null) {
            return false;
        }
        String dcreds = new String(Base64.decodeBase64(creds.getBytes()));
        String[] t = dcreds.split(":");
        if (t.length != 2) {
            return false;
        }
        securityContextMapper.setUsername(t[0]);

        return authHelper.authenticate(queryId, queryOnResource, t[0], t[1], securityContextMapper);
    }

    /**
     * No work to do here so always returns AuthStatus.SEND_SUCCESS.
     *
     * @param messageInfo {@inheritDoc}
     * @param serviceSubject {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) {
        return super.secureResponse(messageInfo, serviceSubject);
    }

    /**
     * Nothing to clean up.
     *
     * @param messageInfo {@inheritDoc}
     * @param subject {@inheritDoc}
     */
    @Override
    public void cleanSubject(MessageInfo messageInfo, Subject subject) {
    }
}