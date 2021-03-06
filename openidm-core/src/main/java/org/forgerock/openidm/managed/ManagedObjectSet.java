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
 * Portions copyright 2011-2015 ForgeRock AS.
 */
package org.forgerock.openidm.managed;

import static org.forgerock.json.JsonValue.field;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.json.resource.ResourceResponse.FIELD_CONTENT_ID;
import static org.forgerock.json.resource.Responses.newActionResponse;
import static org.forgerock.json.resource.Responses.newResourceResponse;
import static org.forgerock.openidm.managed.ManagedObjectSet.ScriptHook.onRead;
import static org.forgerock.util.promise.Promises.newResultPromise;
import static org.forgerock.util.promise.Promises.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import javax.script.ScriptException;

import org.forgerock.json.JsonException;
import org.forgerock.json.JsonPointer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.JsonValueException;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.CollectionResourceProvider;
import org.forgerock.json.resource.ConnectionFactory;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.ForbiddenException;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotFoundException;
import org.forgerock.json.resource.PatchOperation;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.PreconditionFailedException;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.Request;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourcePath;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.Responses;
import org.forgerock.json.resource.SortKey;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openidm.audit.util.ActivityLogger;
import org.forgerock.openidm.audit.util.RouterActivityLogger;
import org.forgerock.openidm.audit.util.Status;
import org.forgerock.openidm.core.IdentityServer;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.crypto.CryptoService;
import org.forgerock.openidm.patch.JsonValuePatch;
import org.forgerock.openidm.router.RouteService;
import org.forgerock.openidm.sync.impl.SynchronizationService;
import org.forgerock.openidm.util.ContextUtil;
import org.forgerock.openidm.util.RequestUtil;
import org.forgerock.script.Script;
import org.forgerock.script.ScriptEntry;
import org.forgerock.script.ScriptEvent;
import org.forgerock.script.ScriptListener;
import org.forgerock.script.ScriptRegistry;
import org.forgerock.script.exception.ScriptThrownException;
import org.forgerock.services.context.Context;
import org.forgerock.util.Function;
import org.forgerock.util.Pair;
import org.forgerock.util.promise.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides access to a set of managed objects of a given type: managed/[type]/{id}.
 *
 */
class ManagedObjectSet implements CollectionResourceProvider, ScriptListener, ManagedObjectSyncService {

    /** Actions supported by this resource provider */
    enum Action {
        patch,
        triggerSyncCheck
    }

    /** Supported script hooks */
    enum ScriptHook {
        /** Script to execute when the creation of an object is being requested. */
        onCreate,

        /** Script to execute when the read of an object is being requested. */
        onRead,

        /** Script to execute when the update of an object is being requested. */
        onUpdate,

        /** Script to execute when the deletion of an object is being requested. */
        onDelete,

        /** Script to execute after the create of an object has completed. */
        postCreate,

        /** Script to execute after the update of an object has completed. */
        postUpdate,

        /** Script to execute after the delete of an object has completed. */
        postDelete,

        /** Script to execute when a managed object requires validation. */
        onValidate,

        /** Script to execute once an object is retrieved from the repository. */
        onRetrieve,

        /** Script to execute when an object is about to be stored in the repository. */
        onStore,

        /** Script to execute when synchronization of managed objects to external targets is complete. */
        onSync
    }

    /**
     * Setup logging for the {@link ManagedObjectSet}.
     */
    private static final Logger logger = LoggerFactory.getLogger(ManagedObjectSet.class);

    /** The managed objects service that instantiated this managed object set. */
    private final CryptoService cryptoService;

    /** The connection factory for access to the router */
    private final ConnectionFactory connectionFactory;

    /** Audit Activity Log helper */
    private final ActivityLogger activityLogger;

    /** Name of the managed object type. */
    private final String name;

    /** the managed object path (e.g. managed/user) as a ResourcePath */
    private final ResourcePath managedObjectPath;

    /** The schema to use to validate the structure and content of the managed object. */
    private final ManagedObjectSchema schema;

    /** Map of scripts to execute on specific {@link ScriptHook}s. */
    private final Map<ScriptHook, ScriptEntry> scriptHooks = new EnumMap<ScriptHook, ScriptEntry>(ScriptHook.class);

    /** Properties for which triggers are executed during object set operations. */
    private final ArrayList<ManagedObjectProperty> properties = new ArrayList<ManagedObjectProperty>();

    /** reference to the sync service route; used to decided whether or not to perform a sync action */
    private final AtomicReference<RouteService> syncRoute;

    /** Map of relationship property names and their accompanying sets */
    private final Map<JsonPointer, RelationshipProvider> relationshipProviders = new HashMap<>();

    /** Flag for indicating if policy enforcement is enabled */
    private final boolean enforcePolicies;

    /**
     * Constructs a new managed object set.
     *
     * @param scriptRegistry
     *            the script registry
     * @param cryptoService
     *            the cryptographic service
     * @param syncRoute
     *            a reference to the RouteService on "sync"
     * @param connectionFactory
     *            the router connection factory
     * @param config
     *            configuration object to use to initialize managed object set.
     * @throws JsonValueException
     *             when the configuration is malformed
     * @throws ScriptException
     *             when the script configuration is malformed or the script is
     *             invalid.
     */
    public ManagedObjectSet(final ScriptRegistry scriptRegistry, final CryptoService cryptoService,
            final AtomicReference<RouteService> syncRoute, ConnectionFactory connectionFactory, JsonValue config)
            throws JsonValueException, ScriptException {
        this.cryptoService = cryptoService;
        this.syncRoute = syncRoute;
        this.connectionFactory = connectionFactory;
        this.activityLogger = new RouterActivityLogger(connectionFactory);
        name = config.get("name").required().asString();
        if (name.trim().isEmpty() || name.indexOf('{') > 0 | name.indexOf('}') > 0) {
            throw new JsonValueException(config.get("name"), "Failed to validate the name");
        }
        this.managedObjectPath = new ResourcePath("managed").child(name);

        schema = new ManagedObjectSchema(config.get("schema").expect(Map.class));

        for (JsonPointer relationship : schema.getRelationshipFields()) {
            final SchemaField field = schema.getField(relationship);
            relationshipProviders.put(relationship, RelationshipProvider.newProvider(connectionFactory, 
                    managedObjectPath, field, activityLogger, this));
        }
        
        for (ScriptHook hook : ScriptHook.values()) {
            if (config.isDefined(hook.name())) {
                scriptHooks.put(hook, scriptRegistry.takeScript(config.get(hook.name())));
            }
        }
        for (JsonValue property : config.get("properties").expect(List.class)) {
            properties.add(new ManagedObjectProperty(scriptRegistry, cryptoService, property));
        }
        enforcePolicies = Boolean.parseBoolean(IdentityServer.getInstance()
                .getProperty("openidm.policy.enforcement.enabled", "true"));
        logger.debug("Instantiated managed object set: {}", name);
    }

    /**
     * Generates a fully-qualified object identifier for the managed object.
     *
     * @param resourceId
     *            the local managed object identifier to qualify.
     * @return the fully-qualified managed object identifier.
     */
    private ResourcePath managedId(String resourceId) {
        return resourceId != null
                ? managedObjectPath.child(resourceId)
                : managedObjectPath;
    }

    /**
     * Generates a fully-qualified object identifier for the repository.
     *
     * @param resourceId
     *            the local managed object identifier to qualify.
     * @return the fully-qualified repository object identifier.
     */
    private String repoId(String resourceId) {
        return ResourcePath.valueOf("repo").concat(managedId(resourceId)).toString();
    }

    /**
     * Executes a script if it exists, populating an {@code "object"} property in the root scope.
     *
     * @param hook
     *            the script-hook to execute
     * @param value
     *            the object to be populated in the script scope.
     * @param additionalProps
     *            a Map of additional properties to add the the script scope
     * @throws ForbiddenException
     *             if the script throws an exception.
     * @throws InternalServerErrorException
     *             if any other exception is encountered.
     */
    private void execScript(final Context context, ScriptHook hook, JsonValue value, JsonValue additionalProps)
            throws ResourceException {
        final ScriptEntry scriptEntry = scriptHooks.get(hook);
        if (null != scriptEntry && scriptEntry.isActive()) {
            Script script = scriptEntry.getScript(context);
            script.put("object", value);
            if (additionalProps != null && !additionalProps.isNull()) {
                for (String key : additionalProps.keys()) {
                    script.put(key, additionalProps.get(key));
                }
            }
            try {
                script.eval(); // allows direct modification to the object
            } catch (ScriptThrownException ste) {
                // Allow for scripts to set their own exception
                throw ste.toResourceException(ResourceException.INTERNAL_ERROR,
                        hook.name() + " script encountered exception");
            } catch (ScriptException se) {
                String msg = hook.name() + " script encountered exception";
                logger.debug(msg, se);
                throw new InternalServerErrorException(msg, se);
            }
        }
    }

    /**
     * Prepares a map of additional bindings for the script hook invocation.
     *
     * @param context the current Context
     * @param request the Request being processed
     * @param resourceId the resourceId of the object being manipulated
     * @param oldObject the old object value
     * @param newObject the new object value
     * @return a JsonValue map of script bindings
     */
    private JsonValue prepareScriptBindings(Context context, Request request, String resourceId,
            JsonValue oldObject, JsonValue newObject) {
        JsonValue scriptBindings = json(object());
        scriptBindings.put("context", context);
        scriptBindings.put("request", request);
        scriptBindings.put("oldObject", oldObject.getObject());
        scriptBindings.put("newObject", newObject.getObject());
        // TODO once SCRIPT-1 is implemented, this can be removed and the resourceName can be obtained via context.router.getBaseUri()
        scriptBindings.put("resourceName", managedId(resourceId));
        return scriptBindings;
    }

    /**
     * Executes all of the necessary trigger scripts when an object is retrieved from the repository.
     *
     * @param context the current Context
     * @param request the Request being processed
     * @param resourceId the resourceId of the object being manipulated
     * @param value
     *            the JSON value that was retrieved from the repository.
     * @throws ForbiddenException
     *             if a validation trigger throws an exception.
     * @throws InternalServerErrorException
     *             if any other exception occurs.
     */
    private void onRetrieve(Context context, Request request, String resourceId, ResourceResponse value) throws ResourceException {
        execScript(context, ScriptHook.onRetrieve, value.getContent(),
                prepareScriptBindings(context, request, resourceId, new JsonValue(null), new JsonValue(null)));
        for (ManagedObjectProperty property : properties) {
            property.onRetrieve(context, value.getContent());
        }
    }

    private void populateVirtualProperties(Context context, JsonValue content) throws ForbiddenException,
            InternalServerErrorException {
        for (ManagedObjectProperty property : properties) {
            if (property.isVirtual()) {
                property.onRetrieve(context, content);
            }
        }
    }

    /**
     * Executes all of the necessary trigger scripts when an object is to be stored in the repository.
     *
     * @param value
     *            the JSON value to be stored in the repository.
     * @throws ForbiddenException
     *             if a validation trigger throws an exception.
     * @throws InternalServerErrorException
     *             if any other exception occurs.
     */
    private void onStore(Context context, JsonValue value) throws ResourceException  {
        for (ManagedObjectProperty property : properties) {
            property.onValidate(context, value);
        }
        execScript(context, ScriptHook.onValidate, value, null);
        // TODO: schema validation here (w. optimizations)
        for (ManagedObjectProperty property : properties) {
            property.onStore(context, value); // includes per-property encryption
        }
        execScript(context, ScriptHook.onStore, value, null);
    }

    /**
     * Decrypt the value
     *
     * @param value
     *            a json value with potentially encrypted value(s)
     * @return object with values decrypted
     * @throws InternalServerErrorException
     *             if decryption failed for any reason
     */
    private JsonValue decrypt(final JsonValue value) throws InternalServerErrorException {
        try {
            return cryptoService.decrypt(value); // makes a copy, which we can modify
        } catch (JsonException je) {
            throw new InternalServerErrorException(je);
        }
    }

    /**
     * Decrypt the value
     *
     * @param value
     *            a json value with potentially encrypted value(s)
     * @return object with values decrypted
     * @throws InternalServerErrorException
     *             if decryption failed for any reason
     */
    private ResourceResponse decrypt(final ResourceResponse value) throws InternalServerErrorException {
        try {
            // makes a copy, which we can modify
            return newResourceResponse(value.getId(), value.getRevision(),
                    null != value.getContent() ? cryptoService.decrypt(value.getContent()) : null);
        } catch (JsonException je) {
            throw new InternalServerErrorException(je);
        }
    }

    /**
     * Forbid the use of sub objects
     *
     * @param id
     *            the identifier to check
     * @throws ForbiddenException
     *             if the identifier identifies a sub object
     */
    private void noSubObjects(String id) throws ForbiddenException {
        if (id != null && id.indexOf('/') >= 0) {
            throw new ForbiddenException("Sub-objects are not supported");
        }
    }

    /**
     * Forbid operation without id, on the whole object set
     *
     * @param id
     *            the identifier to check
     * @throws ForbiddenException
     *             if there is no identifier.
     */
    private void idRequired(String id) throws ForbiddenException {
        if (id == null) {
            throw new ForbiddenException("Operation not allowed on entire object set");
        }
    }

    /**
     * Update a resource as part of an update or patch request.
     *
     * @param context the current Context
     * @param request the source Request
     * @param resourceId the resource id of the object being modified
     * @param rev the revision of hte object being modified
     * @param oldValue the old value of the object
     * @param newValue the new value of the object
     * @return a {@link ResourceResponse} object representing the updated resource
     * @throws ResourceException
     */
    private ResourceResponse update(final Context context, Request request, String resourceId, String rev,
    		JsonValue oldValue, JsonValue newValue)
            throws ResourceException {

        if (newValue.asMap().equals(oldValue.asMap())) { // object hasn't changed
            return newResourceResponse(resourceId, rev, null);
        }

        // Persists all relationship fields that are present in the new value and updates their values.
        newValue.asMap().putAll(persistRelationships(context, resourceId, newValue).asMap());

        // Execute the onUpdate script if configured
        execScript(context, ScriptHook.onUpdate, newValue,
                prepareScriptBindings(context, request, resourceId, oldValue, newValue));

        // Populate the virtual properties (so they are updated for sync-ing)
        populateVirtualProperties(context, newValue);

        // Remove relationships so they don't get persisted in the repository with the managed object details.
        JsonValue strippedRelationshipFields = stripRelationshipFields(newValue);

        // Perform pre-property encryption
        onStore(context, newValue); // performs per-property encryption

        // Perform update
        UpdateRequest updateRequest = Requests.newUpdateRequest(repoId(resourceId), newValue);
        updateRequest.setRevision(rev);
        ResourceResponse response = connectionFactory.getConnection().update(context, updateRequest);
        
        // Put relationships back in before we respond
        response.getContent().asMap().putAll(strippedRelationshipFields.asMap());

        // Execute the postUpdate script if configured
        execScript(context, ScriptHook.postUpdate, response.getContent(),
                prepareScriptBindings(context, request, resourceId, oldValue, response.getContent()));

        performSyncAction(context, request, resourceId, SynchronizationService.SyncServiceAction.notifyUpdate,
                oldValue, response.getContent());

        return response;
    }

    /**
     * Persist all relationship fields contained in the JsonValue map to their accompanying
     * {@link #relationshipProviders}
     *
     * @param context The current context
     * @param resourceId The id of the resource these relationships are associated with
     * @param json A JsonValue map that contains relationship fields and value(s) to be persisted
     * @return A {@link JsonValue} map containing each relationship field and its persisted value(s)
     */
    private JsonValue persistRelationships(Context context, String resourceId, final JsonValue json) 
            throws ResourceException {
        final List<Promise<JsonValue, ResourceException>> persisted = new ArrayList<>();

        for (final JsonPointer relationshipField : relationshipProviders.keySet()) {
            final JsonValue relationshipValue = json.expect(Map.class).get(relationshipField);

            if (relationshipValue != null) {
                RelationshipProvider provider = relationshipProviders.get(relationshipField);
                persisted.add(provider.setRelationshipValueForResource(new ManagedObjectSetContext(context), resourceId, 
                        relationshipValue).then(new Function<JsonValue, JsonValue, ResourceException>() {
                            @Override
                            public JsonValue apply(JsonValue jsonValue) throws ResourceException {
                                return json(object(field(relationshipField.leaf(), jsonValue.getObject())));
                            }
                        }
                ));
            }
        }

        return when(persisted).then(new Function<List<JsonValue>, JsonValue, ResourceException>() {
            @Override
            public JsonValue apply(List<JsonValue> jsonValues) throws ResourceException {
                final JsonValue joined = json(object());

                // Join json maps
                for (JsonValue value : jsonValues) {
                    joined.asMap().putAll(value.asMap());
                }

                return joined;
            }
        }).getOrThrowUninterruptibly();
    }

    /**
     * Applies a patch document to an object, or by finding an object in the object set itself via query parameters. As 
     * this is an action, the patch document to be applied is in the {@code _entity} parameter.
     *
     * @param context the current Context
     * @param request the {@link ActionRequest}
     * @return a {@link ResourceResponse} representing the patched object.
     * @throws ResourceException
     */
    private ResourceResponse patchAction(final Context context, final ActionRequest request) throws ResourceException {
        if (!request.getContent().required().isList()) {
            throw new BadRequestException(
                    "The request could not be processed because the provided content is not a JSON array");
        }

        // Build query request from action parameters looking for query parameters
        // use JsonValue to coerce Map<String, String> to Map<String, Object> - blech
        QueryRequest queryRequest = RequestUtil.buildQueryRequestFromParameterMap(repoId(null),
                new JsonValue(request.getAdditionalParameters()).asMap());

        final List<PatchOperation> operations = PatchOperation.valueOfList(request.getContent());
        final List<ResourceResponse> resources = new ArrayList<ResourceResponse>();

        connectionFactory.getConnection().query(context, queryRequest,
                new QueryResourceHandler() {
                    @Override
                    public boolean handleResource(ResourceResponse resource) {
                        logger.debug("Patch by query found resource " + resource.getId());
                        resources.add(resource);
                        return true;
                    }
                });
        if (resources.size() == 1) {
            try {
            	return patchResource(context, request, resources.get(0), null, operations);
            } catch (ResourceException e) {
                throw e;
            } catch (Exception e) {
            	throw new InternalServerErrorException(e.getMessage(), e);
            }
        } else if (resources.size() > 1) {
        	throw new InternalServerErrorException("Query result must yield one matching object");
        } else {
            throw new NotFoundException("Query returned no results");
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException>  createInstance(Context context, CreateRequest request) {
        String resourceId = request.getNewResourceId();
        JsonValue content = request.getContent();

        // Check if the new id is specified in content, and use it if it is.
        if (!content.get(FIELD_CONTENT_ID).isNull()) {
            resourceId = content.get(FIELD_CONTENT_ID).asString();
        }
        logger.debug("Create name={} id={}", name, resourceId);

        try {
            // decrypt any incoming encrypted properties
            JsonValue value = decrypt(content);

            // Execute onCreate script
            execScript(context, ScriptHook.onCreate, value, null);
            
            // Populate the virtual properties (so they are available for sync-ing)
            populateVirtualProperties(context, value);

            // Remove relationships so they don't get persisted in the repository with the managed object details.
            JsonValue strippedRelationshipFields = stripRelationshipFields(value);

            // includes per-property encryption
            onStore(context, value);

            // Persist the managed object in the repository
            CreateRequest createRequest = Requests.newCreateRequest(repoId(null), resourceId, value);
            ResourceResponse createResponse = connectionFactory.getConnection().create(context, createRequest);
            content = createResponse.getContent();
            resourceId = createResponse.getId();

            activityLogger.log(context, request, "create", managedId(resourceId).toString(), null, content, 
                    Status.SUCCESS);
            
            // Place stripped relationships back in content
            content.asMap().putAll(strippedRelationshipFields.asMap());
            
            // Persists all relationship fields that are present in the created object and updates their values.
            content.asMap().putAll(persistRelationships(context, resourceId, content).asMap());

            // Execute the postCreate script if configured
            execScript(context, ScriptHook.postCreate, content,
                    prepareScriptBindings(context, request, resourceId, new JsonValue(null), content));

            // Sync any targets after managed object is created
            performSyncAction(context, request, resourceId, SynchronizationService.SyncServiceAction.notifyCreate,
                    new JsonValue(null), content);
            
            return prepareResponse(context, createResponse, request.getFields()).asPromise();
        } catch (ResourceException e) {
        	return e.asPromise();
        } catch (Exception e) {
        	return new InternalServerErrorException(e.getMessage(), e).asPromise();
        }
    }
    @Override
    public Promise<ResourceResponse, ResourceException> readInstance(final Context context, String resourceId, 
    		ReadRequest request) {
        logger.debug("Read name={} id={}", name, resourceId);
        try {

            ReadRequest readRequest = Requests.newReadRequest(repoId(resourceId));
            ResourceResponse readResponse = connectionFactory.getConnection().read(context, readRequest);

            final JsonValue relationships = fetchRelationshipFields(context, resourceId);
            readResponse.getContent().asMap().putAll(relationships.asMap());

            onRetrieve(context, request, resourceId, readResponse);
            execScript(context, onRead, readResponse.getContent(), null);
            activityLogger.log(context, request, "read", managedId(readResponse.getId()).toString(),
                    null, readResponse.getContent(), Status.SUCCESS);
            
            return prepareResponse(context, readResponse, request.getFields()).asPromise();
        } catch (ResourceException e) {
        	return e.asPromise();
        } catch (Exception e) {
        	return new InternalServerErrorException(e.getMessage(), e).asPromise();
        }
    }

    /**
     * Fetch the current relationship(s) for all relationship fields.
     *
     * @param context The current context
     * @param resourceId The id of the resource to fetch relationships of
     * @return A {@link JsonValue} map containing all relationship fields and their values
     * @throws ResourceException 
     */
    private JsonValue fetchRelationshipFields(Context context, String resourceId) 
            throws ExecutionException, InterruptedException, ResourceException {
        final JsonValue joined = json(object());

        for (Map.Entry<JsonPointer, RelationshipProvider> entry : relationshipProviders.entrySet()) {
            final JsonPointer field = entry.getKey();
            final RelationshipProvider provider = entry.getValue();
            
            try {
                joined.put(field, provider.getRelationshipValueForResource(new ManagedObjectSetContext(context), 
                        resourceId).getOrThrow().getObject());
            } catch (NotFoundException e) {
                joined.put(field, null);
            }
        }

        return joined;
    }

    @Override
    public Promise<ResourceResponse, ResourceException>  updateInstance(final Context context, final String resourceId, 
    		final UpdateRequest request) {
        logger.debug("update {} ", "name=" + name + " id=" + resourceId + " rev="
                + request.getRevision());

        try {
            // decrypt any incoming encrypted properties
            JsonValue _new = decrypt(request.getContent());

            ReadRequest readRequest = Requests.newReadRequest(repoId(resourceId));
            for (JsonPointer pointer : request.getFields()) {
                readRequest.addField(pointer);
            }
            ResourceResponse readResponse = connectionFactory.getConnection().read(context, readRequest);
            ResourceResponse decryptedResponse = decrypt(readResponse);

            ResourceResponse updatedResponse = update(context, request, resourceId, request.getRevision(),
            		decryptedResponse.getContent(), _new);
            
            activityLogger.log(context, request, "update", managedId(readResponse.getId()).toString(),
                    readResponse.getContent(), updatedResponse.getContent(), Status.SUCCESS);

            return prepareResponse(context, updatedResponse, request.getFields()).asPromise();
        } catch (ResourceException e) {
        	return e.asPromise();
        } catch (Exception e) {
        	return new InternalServerErrorException(e.getMessage(), e).asPromise();
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException> deleteInstance(final Context context, final String resourceId, 
    		final DeleteRequest request) {
        logger.debug("Delete {} ", "name=" + name + " id=" + resourceId + " rev="
                + request.getRevision());
        try {
            ReadRequest readRequest = Requests.newReadRequest(repoId(resourceId));
            ResourceResponse resource = connectionFactory.getConnection().read(context, readRequest);
            
            // Populate the relationship fields in the read resource
            final JsonValue relationships = fetchRelationshipFields(context, resourceId);
            resource.getContent().asMap().putAll(relationships.asMap());
            
            execScript(context, ScriptHook.onDelete, decrypt(resource.getContent()), null);

            // Delete the resource
            DeleteRequest deleteRequest = Requests.newDeleteRequest(repoId(resourceId));

            if (request.getRevision() != null) {
                deleteRequest.setRevision(request.getRevision());
            } else {
                deleteRequest.setRevision(resource.getRevision());
            }

            connectionFactory.getConnection().delete(context, deleteRequest);

            // Delete any relationships associated with this resource
            final List<Promise<JsonValue, ResourceException>> deleted = new ArrayList<>();
            for (RelationshipProvider relationshipProvider : relationshipProviders.values()) {
                deleted.add(relationshipProvider.clear(new ManagedObjectSetContext(context), resourceId));
            }
            // Wait for deletions to complete before continuing
            when(deleted).getOrThrowUninterruptibly();

            activityLogger.log(context, request, "delete", managedId(resource.getId()).toString(),
                    resource.getContent(), null, Status.SUCCESS);

            // Execute the postDelete script if configured
            execScript(context, ScriptHook.postDelete, null, prepareScriptBindings(context, request, resourceId,
                    resource.getContent(), new JsonValue(null)));

            // Perform notifyDelete synchronization
            performSyncAction(context, request, resourceId, SynchronizationService.SyncServiceAction.notifyDelete,
                    resource.getContent(), new JsonValue(null));

            return prepareResponse(context, resource, request.getFields()).asPromise();
        } catch (ResourceException e) {
        	return e.asPromise();
        } catch (Exception e) {
        	return new InternalServerErrorException(e.getMessage(), e).asPromise();
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException> patchInstance(Context context, String resourceId, 
    		PatchRequest request) {
        try {
        	return newResultPromise(patchResourceById(context, request, resourceId, request.getRevision(),
                    request.getPatchOperations()));
        } catch (ResourceException e) {
        	return e.asPromise();
        }
    }

    /**
     * Patches the given resource and will also remove private properties if it is an external call based upon context.
     *
     * @param context
     * @param request
     * @param resourceId
     * @param revision Expected revision of the resource. Patch will fail if non-null and not matching.
     * @param patchOperations
     *
     * @return The patched ResourceResponse with private properties omitted if called externally.
     *
     * @throws ResourceException
     */
    private ResourceResponse patchResourceById(Context context, Request request,
                                       String resourceId, String revision, List<PatchOperation> patchOperations)
            throws ResourceException {
        idRequired(request.getResourcePath());
        noSubObjects(request.getResourcePath());

        // Get the oldest value for diffing in the log
        // JsonValue oldValue = new JsonValue(cryptoService.getRouter().read(repoId(id)));
        ReadRequest readRequest = Requests.newReadRequest(repoId(resourceId));
        ResourceResponse resource = connectionFactory.getConnection().read(context, readRequest);

        return patchResource(context, request, resource, revision, patchOperations);
    }

    /**
     * Patches the given resource and will also remove private properties if it is an external call based upon context.
     *
     * @param context
     * @param request
     * @param resource The resource to be patched
     * @param revision
     * @param patchOperations
     *
     * @return The patched ResourceResponse with private properties omitted if called externally.
     *
     * @throws ResourceException
     */
    private ResourceResponse patchResource(Context context, Request request,
    		ResourceResponse resource, String revision, List<PatchOperation> patchOperations)
        throws ResourceException {

        // FIXME: There's no way to decrypt a patch document. :-( Luckily, it'll work for now with patch action.

        boolean forceUpdate = (revision == null);
        boolean retry = forceUpdate;
        String rev = revision;

        do {
            logger.debug("patch name={} id={}", name, request.getResourcePath());
            try {

            	// decrypt any incoming encrypted properties
                JsonValue decrypted = decrypt(resource.getContent());

                // If we haven't defined a revision, we need to get the current revision
                if (revision == null) {
                    rev = decrypted.get("_rev").asString();
                }
                
                // Populate the relainshionships
                final JsonValue relationships = fetchRelationshipFields(context, resource.getId());
                decrypted.asMap().putAll(relationships.asMap());

                JsonValue newValue = decrypted.copy();
                boolean modified = JsonValuePatch.apply(newValue, patchOperations);
                if (!modified) {
                    return null;
                }

                // Check if policies should be enforced
                if (enforcePolicies) {
                    ActionRequest policyAction = Requests.newActionRequest(
                            ResourcePath.valueOf("policy").concat(managedId(resource.getId())).toString(), "validateObject");
                    policyAction.setContent(newValue);
                    if (ContextUtil.isExternal(context)) {
                        // this parameter is used in conjunction with the test in policy.js
                        // to ensure that the reauth policy is enforced
                        policyAction.setAdditionalParameter("external", "true");
                    }

                    JsonValue result = connectionFactory.getConnection().action(context, policyAction).getJsonContent();
                    if (!result.isNull() && !result.get("result").asBoolean()) {
                        logger.debug("Requested patch failed policy validation: {}", result);
                        throw new ForbiddenException("Failed policy validation").setDetail(result);
                    }
                }

                ResourceResponse patchedResource = update(context, request, resource.getId(), rev, resource.getContent(), newValue);

                activityLogger.log(context, request, "",
                        managedId(patchedResource.getId()).toString(), resource.getContent(), patchedResource.getContent(),
                        Status.SUCCESS);
                retry = false;
                logger.debug("Patch successful!");

                return prepareResponse(context, patchedResource, request.getFields());
            } catch (PreconditionFailedException e) {
                if (forceUpdate) {
                    logger.debug("Unable to update due to revision conflict. Retrying.");
                } else {
                    // If it fails and we're not trying to force an update, we gave it our best shot
                    throw e;
                }
            } catch (ResourceException e) {
                throw e;
            } catch (Exception e) {
                throw new InternalServerErrorException(e.getMessage(), e);
            }
        } while (retry);
        return null;
    }

    @Override
    public Promise<QueryResponse, ResourceException> queryCollection(final Context context, final QueryRequest request,
            final QueryResourceHandler handler) {
        logger.debug("query name={} id={}", name, request.getResourcePath());
        
        // The "executeOnRetrieve" parameter is used to indicate if is returning a full managed object
        String executeOnRetrieve = request.getAdditionalParameter("executeOnRetrieve");
        
        // The onRetrieve script should only be run queries that return full managed objects
        final boolean onRetrieve = executeOnRetrieve == null
                ? false
                : Boolean.parseBoolean(executeOnRetrieve);

        final List<Map<String,Object>> results = new ArrayList<Map<String,Object>>();
        final ResourceException[] ex = new ResourceException[]{null};
        try {
            // Create new QueryRequest to send to the repository
            // Does not include any fields specified in the current request
            QueryRequest repoRequest = Requests.newQueryRequest(repoId(null));
            repoRequest.setQueryId(request.getQueryId());
            repoRequest.setQueryFilter(request.getQueryFilter());
            repoRequest.setQueryExpression(request.getQueryExpression());
            repoRequest.setPageSize(request.getPageSize());
            repoRequest.setPagedResultsOffset(request.getPagedResultsOffset());
            repoRequest.setPagedResultsCookie(request.getPagedResultsCookie());
            repoRequest.setTotalPagedResultsPolicy(request.getTotalPagedResultsPolicy());
            repoRequest.addSortKey(request.getSortKeys().toArray(new SortKey[request.getSortKeys().size()]));
            for (String key : request.getAdditionalParameters().keySet()) {
                repoRequest.setAdditionalParameter(key, request.getAdditionalParameter(key));
            }
        	
        	QueryResponse queryResponse = connectionFactory.getConnection().query(context, repoRequest,
            		new QueryResourceHandler() {
                @Override
                public boolean handleResource(ResourceResponse resource) {
                    ResourceResponse resourceResponse = null;
                    // Check if the onRetrieve script should be run
                    if (onRetrieve) {
                        try {
                            onRetrieve(context, request, resource.getId(), resource);
                        } catch (ResourceException e) {
                        	ex[0] = e;
                            return false;
                        }
                    }
                    if (ServerConstants.QUERY_ALL_IDS.equals(request.getQueryId())) {
                        // Don't populate relationships if this is a query-all-ids query.
                        resourceResponse = resource;    
                    } else {
                        // Populate the relationship fields
                        try {
                            JsonValue relationships = fetchRelationshipFields(context, resource.getId());
                            resource.getContent().asMap().putAll(relationships.asMap());
                            resourceResponse = prepareResponse(context, resource, request.getFields());
                        } catch (ResourceException e) {
                            ex[0] = e;
                            return false;
                        } catch (Exception e) {
                            ex[0] = new InternalServerErrorException(e.getMessage(), e);
                            return false;
                        }
                    }
                    results.add(resourceResponse.getContent().asMap());
                    return handler.handleResource(prepareResponse(context, resourceResponse, request.getFields()));
                }
            });
        	
        	if(ex[0] != null) {
            	return ex[0].asPromise();
        	}
        	
            activityLogger.log(context, request, 
            		"query: " + request.getQueryId() + ", parameters: " + request.getAdditionalParameters(), 
            		request.getQueryId(), null, new JsonValue(results), Status.SUCCESS);
            
        	return queryResponse.asPromise();

        } catch (ResourceException e) {
        	return e.asPromise();
        }
    }

    @Override
    public Promise<ActionResponse, ResourceException> actionInstance(Context context, String resourceId, 
    		ActionRequest request) {

        try {
            activityLogger.log(context, request, "Action: " + request.getAction(),
                    managedId(resourceId).toString(), null, null, Status.SUCCESS);
            switch (request.getActionAsEnum(Action.class)) {
                case patch:
                    final List<PatchOperation> operations = PatchOperation.valueOfList(request.getContent());
                    ResourceResponse patchResponse = patchResourceById(context, request, resourceId, null, operations);
                    return newActionResponse(patchResponse.getContent()).asPromise();
                case triggerSyncCheck:
                    // Sync changes if required, in particular virtual/calculated attribute changes
                    final ReadRequest readRequest = Requests.newReadRequest(managedId(resourceId).toString());
                    logger.debug("Attempt sync of {}", readRequest.getResourcePath());
                    ResourceResponse currentResource = connectionFactory.getConnection().read(context, readRequest);
                    UpdateRequest updateRequest = Requests.newUpdateRequest(readRequest.getResourcePath(),
                    		currentResource.getContent());
                    ResourceResponse updateResponse = updateInstance(context, resourceId, updateRequest).get();
                    logger.debug("Sync of {} complete", readRequest.getResourcePath());
                    return Responses.newActionResponse(updateResponse.getContent()).asPromise();
                default:
                    throw new BadRequestException("Action " + request.getAction() + " is not supported.");
            }
        } catch (ResourceException e) {
        	return e.asPromise();
        } catch (IllegalArgumentException e) { 
        	// from getActionAsEnum
        	return new BadRequestException(e.getMessage(), e).asPromise();
        } catch (Exception e) {
        	return new InternalServerErrorException(e.getMessage(), e).asPromise();
        }
    }

    /**
     * Processes action requests.
     * <p>
     * If the {@code _action} parameter is {@code patch}, then the request is
     * handled as a partial modification to an object, either explicitly
     * (identifier is supplied) or by query (query parameters specify the query
     * to perform to yield a single object to patch.
     */
    @Override
    public Promise<ActionResponse, ResourceException> actionCollection(Context context, ActionRequest request) {
        logger.debug("action name={} id={}", name, request.getResourcePath());

        try {
            activityLogger.log(context, request, "Action: " + request.getAction(),
                    request.getResourcePath(), null, null, Status.SUCCESS);
            switch (request.getActionAsEnum(Action.class)) {
                case patch:
                    return newActionResponse(patchAction(context, request).getContent()).asPromise();
                default:
                    throw new BadRequestException("Action " + request.getAction() + " is not supported.");
            }
        } catch (ResourceException e) {
        	return e.asPromise();
        } catch (IllegalArgumentException e) { 
        	// from getActionAsEnum
        	return new BadRequestException(e.getMessage(), e).asPromise();
        }
    }

    // -------- Implements the ScriptListener

    @Override
    public void scriptChanged(ScriptEvent event) throws ScriptException {
        switch (event.getType()) {
        case ScriptEvent.UNREGISTERING:
            logger.error("Script {} became unavailable", event.getScriptLibraryEntry().getName());
        }
    }

    /**
     * Returns the name of the managed object set.
     */
    public String getName() {
        return name;
    }

    public String getTemplate() {
        return name.indexOf('/') == 0 ? name : '/' + name;
    }
    

    /**
     * Prepares the response contents by removing the following: any private properties (if the request is from an 
     * external call), any virtual or relationship properties that are not set to returnByDefault.
     * 
     * @param context the current ServerContext
     * @param resource the Resource to prepare
     * @param fields a list of fields to return specified in the request
     * @return the prepared Resource object
     * @throws ResourceException 
     */
    private ResourceResponse prepareResponse(Context context, ResourceResponse resource, List<JsonPointer> fields) {
        Map<JsonPointer, SchemaField> fieldsToRemove = new HashMap<>(schema.getHiddenByDefaultFields());
        Map<JsonPointer, List<JsonPointer>> resourceExpansionMap = new HashMap<>();
        if (fields != null && fields.size() > 0) {
            for (JsonPointer field : new ArrayList<>(fields)) {
                if (field.equals(SchemaField.FIELD_ALL_RELATIONSHIPS)) {
                    // Return all relationship fields, so remove them from fieldsToRemove map
                    for (JsonPointer key : schema.getRelationshipFields()) {
                        logger.debug("Allowing field {} to be returned, due to *_ref", key);
                        fieldsToRemove.remove(key);
                        fields.add(key);
                    }
                    fields.remove(field);
                } else if (!field.equals(new JsonPointer("*")) && !field.equals(new JsonPointer(""))){
                    if (schema.hasField(field)) {
                        // Allow the field by removing it from the fieldsToRemove list.
                        logger.debug("Allowing field {} to be returned", field);
                        fieldsToRemove.remove(field);
                    } else {
                        // Check for resource expansion and build up map of fields to expand
                        Pair<JsonPointer, JsonPointer> expansionPair = schema.getResourceExpansionField(field);
                        if (expansionPair != null) {
                            JsonPointer relationshipField = expansionPair.getFirst();
                            // Allow the field by removing it from the fieldsToRemove list (if there)
                            fieldsToRemove.remove(relationshipField);
                            // Add the field to the expansion map
                            if (!resourceExpansionMap.containsKey(relationshipField)) {
                            	// Initialize the list of fields in the resource expansion map
                                resourceExpansionMap.put(relationshipField, new ArrayList<JsonPointer>());
                                // replace the field in the fields list with the relationship field
                                fields.remove(field);
                                fields.add(relationshipField);
                            }
                            resourceExpansionMap.get(relationshipField).add(expansionPair.getSecond());
                        }
                    }

                } else if (field.equals(new JsonPointer("*"))) {
                	fields.remove(field);
                	fields.add(new JsonPointer(""));
                }
            }
        }
        
        // Remove all relationship and virtual fields that are not returned by default, or explicitly listed
        for (JsonPointer key : fieldsToRemove.keySet()) {
            logger.debug("Removing field {} from the response object", key);
            resource.getContent().remove(key);
        }
        
        // Loop over the relationship fields to expand
        for (JsonPointer fieldToExpand : resourceExpansionMap.keySet()) {
            // The schema for the field to expand
            SchemaField schemaField = schema.getField(fieldToExpand);
            // The list of fields to include from the expanded resource
            List<JsonPointer> fieldsList = resourceExpansionMap.get(fieldToExpand);
            // The value of the relationship field
            JsonValue fieldValue = resource.getContent().get(fieldToExpand);
            try {
                // Perform the resource expansion
                if (fieldValue != null && !fieldValue.isNull()) {
                    if (schemaField.isArray()) {
                        // The field is an array of relationship objects
                        for (JsonValue value : fieldValue) {
                            expandResource(context, value, fieldsList);
                        }
                    } else {
                        // The field is a relationship object  
                        expandResource(context, fieldValue, fieldsList);
                    }
                } else {
                    logger.warn("Cannot expand a null relationship object");
                }
            } catch (ResourceException e) {
                logger.error("Error expanding resource " + fieldToExpand + " with value " + fieldValue, e);
            }
        }
        
        // only cull private properties if this is an external call
        if (ContextUtil.isExternal(context)) {
            for (ManagedObjectProperty property : properties) {
                if (property.isPrivate()) {
                    resource.getContent().remove(property.getName());
                }
            }
        }
        
        // Update the list of fields in the response
        if (fields != null && fields.size() > 0) {
        	resource.addField(fields.toArray(new JsonPointer[fields.size()]));
        }
        
        return resource;
    }

    /**
     * Expands the provided resource represented by a {@link JsonValue} relationship object.  A read request  will be 
     * issued for the resource identified by the "_ref" field in the supplied relationship object. A supplied 
     * {@link List} of fields indicates which fields to read and then merge with the relationship object.
     *    
     * @param context the {@link Context} of the request
     * @param value the value of the relationship object
     * @param fieldsList the list of fields to read and merge with the relationship object.
     * @throws ResourceException if an error is encountered.
     */
    private void expandResource(Context context, JsonValue value, List<JsonPointer> fieldsList)
            throws ResourceException {
        if (!value.isNull() && value.get(SchemaField.FIELD_REFERENCE) != null) {
            // Create and issue a read request on the referenced resource with the specified list of fields
            ReadRequest request = Requests.newReadRequest(value.get(SchemaField.FIELD_REFERENCE).asString());
            request.addField(fieldsList.toArray(new JsonPointer[fieldsList.size()]));
            ResourceResponse resource = connectionFactory.getConnection().read(context, request);
            
            // Merge the result with the supplied relationship object
            value.asMap().putAll(resource.getContent().asMap());
        } else {
            logger.warn("Cannot expand a null relationship object");
        }
    }

    /**
     * Removes all relationship fields from the supplied {@link JsonValue} instance of a managed object.  Returns a 
     * {@link JsonValue} object containing the stripped fields.
     *
     * @param value The JsonValue map to strip relationship fields from
     * @return A {@link JsonValue} object containing the stripped fields.
     */
    protected JsonValue stripRelationshipFields(JsonValue value) {
        final JsonValue stripped = json(object());

        for (JsonPointer field : schema.getRelationshipFields()) {
            JsonValue fieldValue = value.get(field);
            stripped.put(field, fieldValue != null ? fieldValue.getObject() : null);
            value.remove(field);
        }

        return stripped;
    }

    @Override
    public void performSyncAction(final Context context, final Request request, final String resourceId,
            final SynchronizationService.SyncServiceAction action, final JsonValue oldValue, final JsonValue newValue)
        throws ResourceException {

        // The "sync" route may be down (unconfigured) or in the process of being re-configured;
        // if this is the case, we don't want a router error on the ActionRequest below.  Just log
        // the warning and return.  When the SynchronizationService comes back up (or when the
        // reconfiguration is complete), the AtomicReference<RouteService> in ManagedObjectService
        // will get set again.
        if (null == syncRoute.get()) {
            logger.warn("Sync service was not available.");
            return;
        }

        try {
            JsonValue content = new JsonValue(new LinkedHashMap<String, Object>(2));
            content.put("oldValue", oldValue.getObject());
            content.put("newValue", newValue.getObject());
            final ActionRequest syncRequest = Requests.newActionRequest("sync", action.name())
                    .setAdditionalParameter(SynchronizationService.ACTION_PARAM_RESOURCE_CONTAINER, managedObjectPath.toString())
                    .setAdditionalParameter(SynchronizationService.ACTION_PARAM_RESOURCE_ID, resourceId)
                    .setContent(content);

            final ResourceException[] syncScriptError = new ResourceException[] { null };
            JsonValue details;
            boolean success = false;
            try {
				ActionResponse actionResponse = connectionFactory.getConnection().action(context, syncRequest);
				success = true;
				details = actionResponse.getJsonContent();
			} catch (ResourceException e) {
				success = false;
				details = e.getDetail();
			} catch (Exception e) {
				success = false;
				details = new InternalServerErrorException(e.getMessage(), e).getDetail();
			}
            
            try {
            	// Execute the sync script
                JsonValue scriptBindings = prepareScriptBindings(context, request, resourceId, oldValue, newValue);
                Map<String,Object> syncResults = new HashMap<String,Object>();
                syncResults.put("success", success);
                syncResults.put("action", action.name());
                syncResults.put("syncDetails", details.getObject());
                scriptBindings.put("syncResults", syncResults);
                execScript(context, ScriptHook.onSync, null, scriptBindings);
            } catch (ResourceException e) {
                logger.warn("Failed executing onSync script on {} {}:{}",
                        syncRequest.getAction(), name, syncRequest.getResourcePath(), e);
            	throw e;
            }
            if (syncScriptError[0] != null) {
                throw syncScriptError[0];
            }
        } catch (NotFoundException e) {
            logger.error("Failed to sync {} {}:{}", action.name(), name, resourceId, e);
            throw e;
        }
    }

    /**
     * Get the {@link ResourcePath} associated with this set.
     * @return The {@link ResourcePath} associated with this object set.
     */
    public ResourcePath getPath() {
        return managedObjectPath;
    }

    /**
     * Get the {@link ManagedObjectSchema} associated with this set.
     * @return The {@link ManagedObjectSchema} associated with this object set.
     */
    public ManagedObjectSchema getSchema() {
        return schema;
    }

    /**
     * Get the current map of {@link RelationshipProvider} for each relationship field.
     */
    Map<JsonPointer, RelationshipProvider> getRelationshipProviders() {
        return relationshipProviders;
    }
}
