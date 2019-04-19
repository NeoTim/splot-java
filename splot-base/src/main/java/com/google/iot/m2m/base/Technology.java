/*
 * Copyright (C) 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.iot.m2m.base;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * Interface for discovering, interacting with, and hosting {@link FunctionalEndpoint
 * FunctionalEndpoints}.
 *
 * <p>With the exception of {@link com.google.iot.m2m.local.LocalTechnology}, implementations of
 * this interface allow you to interact with devices that implement the associated technology using
 * the Splot API.
 *
 * <p>For example, a hypothetical Zigbee-based technology would allow you to discover and use
 * Zigbee-based devices as FunctionalEndpoints, whereas an {@link com.google.iot.smcp SMCP}-based
 * technology would allow you to use the Splot API to discover and use SMCP-based devices.
 *
 * <p>Some technologies also support <em>hosting</em> functional endpoints (such as subclasses of
 * {@link com.google.iot.m2m.local.LocalFunctionalEndpoint} or even functional endpoints that are
 * obtained from other unrelated technologies) to other devices. This can be used to expose local
 * functionality to other devices on the technology, or even to implement cross-iot-technology
 * bridges.
 *
 * <p>In addition to the tasks described above, instances of this interface are the primary
 * mechanism for managing *groups*: All instances of {@link Group} have an associated {@link
 * Technology} instance. Not all technologies support groups, but you can create local groups to
 * implement group behavior at the expense of efficiency using {@link
 * com.google.iot.m2m.local.LocalTechnology}, which supports forming groups of arbitrary functional
 * endpoints.
 *
 * <p>While not required, all technologies <em>should</em> implement {@link
 * PersistentStateInterface} to allow them to store persistent state.
 *
 * <h2>Relationships with Functional Endpoints</h2>
 *
 * <p>The relationship between any given functional endpoint and a given technology can be described
 * by one of the following terms: <em>native</em>, <em>hosted</em>, or <em>unrelated</em>:
 *
 * <ul>
 *   <li>A <b>native</B> functional endpoint is owned and managed entirely by a Technology instance,
 *       and often represents functionality which is implemented on a different device. You can
 *       determine this programmatically using {@link Technology#isNative(FunctionalEndpoint)}.
 *   <li>A <b>hosted</b> functional endpoint is not owned or managed by the Technology. Instead, the
 *       technology "hosts" the functionality of the functional endpoint for other devices to use.
 *       You can determine this programmatically using {@link
 *       Technology#isHosted(FunctionalEndpoint)}.
 *   <li>An <b>unrelated</b> functional endpoint has no relationship with the technology. Such a
 *       functional endpoint cannot be used by the technology or participate in groups hosted by the
 *       technology. You can determine this programmatically using {@link
 *       Technology#isAssociatedWith(FunctionalEndpoint)}, which will return {@code false} if
 *       unrelated. The method {@link #host(FunctionalEndpoint)} can be used to make an
 *       <em>unrelated</em> functional endpoint <em>hosted</em>.
 * </ul>
 *
 * <p>In most cases, a functional endpoint is described by only one of the above three labels.
 * However, {@link Group} objects (a special interface that extends {@link FunctionalEndpoint}) are
 * <em>always</em> native to a Technology, but are are often also <em>hosted</em> by the same
 * Technology, too! This is because groups logically exist on more than one device, so they can be
 * both native and (if any hosted functional endpoints are members) hosted.
 */
@SuppressWarnings("unused")
public interface Technology {
    /**
     * Prepares the technology to host local functional endpoints for use by other devices.
     *
     * @throws IOException when there are problems opening the underlying sockets in preparation
     *         for hosting.
     * @throws TechnologyCannotHostException if this technology does not support hosting
     */
    void prepareToHost() throws IOException, TechnologyCannotHostException;

    /**
     * Creates a new mutable set containing all functional endpoints hosted by this technology.
     *
     * @return a collection containing all of the functional endpoints hosted by this Technology
     *     instance.
     */
    default Set<FunctionalEndpoint> copyHostedFunctionalEndpointSet() {
        return new HashSet<>();
    }

    /**
     * Hosts the given functional endpoint, allowing it to be used by other devices which use this
     * technology. Groups native to this technology (such as those found through discovery or
     * created by {@link #createNewGroup()} can be converted to hosted groups using this method.
     * Support for foreign groups (where {@link Group#getTechnology()} returns something other than
     * the object for this technology) is optional and usually not supported.
     *
     * @param fe the functional endpoint to host.
     * @throws UnacceptableFunctionalEndpointException if the technology refuses to host the given
     *     FE
     * @throws TechnologyCannotHostException if this technology does not support hosting
     */
    void host(FunctionalEndpoint fe)
            throws UnacceptableFunctionalEndpointException, TechnologyCannotHostException;

    /**
     * Unhosts the given functional endpoint if it was previously hosted.
     *
     * <p>If the given functional endpoint was not hosted by this technology, nothing happens.
     * Calling this method will remove the functional endpoint from any groups associated with
     * this technology. Any saved state about this functional endpoint that was stored by this
     * technology will be forgotten.
     *
     * @param fe the functional endpoint to no longer host
     */
    void unhost(FunctionalEndpoint fe);

    /**
     * Determines if the functional endpoint is hosted by this technology or not.
     *
     * <p>The default implementation uses {@link #copyHostedFunctionalEndpointSet()} to make this
     * determination, and properly handles indicating child functional endpoints as hosted if their
     * parent (as returned by {@link FunctionalEndpoint#getParentFunctionalEndpoint()}) is hosted
     * (up to four levels deep).
     *
     * <p>If you override this method, you should use the following code as a starting point:
     *
     * <pre>{@code
     * FunctionalEndpoint fe;
     * FunctionalEndpoint parent;
     * int parentLimit = 4;
     *
     * do {
     *     if (IS_FE_HOSTED(fe)) {
     *         return true;
     *     }
     *
     *     parent = fe.getParentFunctionalEndpoint();
     *
     *     if (parent == null) {
     *         break;
     *     } else {
     *         fe = parent;
     *     }
     * } while (parentLimit-- != 0);
     *
     * return IS_FE_HOSTED(fe);
     * }</pre>
     *
     * Where {@code IS_FE_HOSTED(fe)} is a method that performs a simple non-recursive hosted check.
     *
     * @param fe The functional endpoint to check
     * @return true if the functional endpoint is currently hosted by this technology, false
     *     otherwise.
     */
    default boolean isHosted(FunctionalEndpoint fe) {
        final Collection<FunctionalEndpoint> hostedFeSet = copyHostedFunctionalEndpointSet();

        FunctionalEndpoint parent;
        int parentLimit = 4;

        do {
            if (hostedFeSet.contains(fe)) {
                return true;
            }

            parent = fe.getParentFunctionalEndpoint();

            if (parent == null) {
                break;
            } else {
                fe = parent;
            }
        } while (parentLimit-- != 0);

        return hostedFeSet.contains(fe);
    }

    /**
     * Determines if a functional endpoint is native to this technology or not. Also works for
     * {@link Group} objects.
     *
     * <p>The default implementation only works for {@link Group Groups} (and the children of those
     * groups) and uses {@link Group#getTechnology()} to determine if it is native.
     *
     * @param fe The functional endpoint to check
     * @return true if the functional endpoint is native to this technology, false otherwise
     */
    default boolean isNative(FunctionalEndpoint fe) {
        FunctionalEndpoint parent;
        int parentLimit = 4;

        do {
            if (((fe instanceof Group) && ((Group) fe).getTechnology() == this)) {
                return true;
            }

            parent = fe.getParentFunctionalEndpoint();

            if (parent == null) {
                break;
            } else {
                fe = parent;
            }
        } while (parentLimit-- != 0);

        return ((fe instanceof Group) && ((Group) fe).getTechnology() == this);
    }

    /**
     * Determines if the functional endpoint is associated with this technology or not. A functional
     * endpoint is considered associated with a technology if it is hosted-by or native-to the
     * technology.
     *
     * @param fe The functional endpoint to check
     * @return true if the functional endpoint is associated with this technology, false otherwise
     */
    default boolean isAssociatedWith(FunctionalEndpoint fe) {
        return isNative(fe) || isHosted(fe);
    }

    /**
     * Returns the functional endpoint that is hosted at the given native URI.
     *
     * <p>The given URI must be a "native" URI that can be instantly resolved to a {@link
     * FunctionalEndpoint} instance. URIs that require being resolved must instead use {@link
     * #lookupFunctionalEndpointForUri(URI)}.
     *
     * @param uri The URI of the functional endpoint
     * @return null if the URI is not recognized, otherwise returns the functional endpoint
     */
    FunctionalEndpoint getFunctionalEndpointForNativeUri(URI uri) throws UnknownResourceException;

    /**
     * Returns the functional endpoint for the given URI.
     *
     * <p>The default implementation simply returns a future that calls {@link
     * #getFunctionalEndpointForNativeUri}.
     *
     * @param uri The URI of the functional endpoint
     * @return ListenableFuture that returns the functional endpoint
     */
    default ListenableFuture<FunctionalEndpoint> lookupFunctionalEndpointForUri(URI uri) {
        try {
            return Futures.immediateFuture(getFunctionalEndpointForNativeUri(uri));
        } catch (UnknownResourceException x) {
            return Futures.immediateFailedFuture(x);
        }
    }

    /**
     * Returns the native URI of the given functional endpoint. The functional endpoint MUST be
     * associated with this technology.
     *
     * <p>The returned URI must be either an absolute URL (include schema and possibly authority) or
     * a absolute-path-only URL (No schema or authority). The format and scheme of the URI is native
     * (technology specific).
     *
     * <p>Note that the returned URI is relative to this device/technology. If you intend to
     * set this URI as a property value on a non-local FunctionalEndpoint, you must first
     * change the URI to be relative to that FunctionalEndpoint using
     * {@link #getRelativeUriForFunctionalEndpoint(FunctionalEndpoint, URI)}.
     *
     * @param fe The functional endpoint to obtain the URI of.
     * @return The URI for the given functional endpoint
     * @throws UnassociatedResourceException if the functional endpoint isn't associated with this
     *                                  technology
     */
    URI getNativeUriForFunctionalEndpoint(FunctionalEndpoint fe) throws UnassociatedResourceException;

    /**
     * Calculates the native URI that represents a specific property on the given
     * {@link FunctionalEndpoint}, with optional {@link Modifier modifiers}.
     *
     * <p>The returned URI must be either an absolute URL (include schema and possibly authority) or
     * a absolute-path-only URL (No schema or authority). The format and scheme of the URI is native
     * (technology specific).
     *
     * <p>Note that the returned URI is relative to this device/technology. If you intend to
     * set this URI as a property value on a non-local FunctionalEndpoint, you must first
     * change the URI to be relative to that FunctionalEndpoint using
     * {@link #getRelativeUriForFunctionalEndpoint(FunctionalEndpoint, URI)}.
     *
     * @param fe the {@link FunctionalEndpoint} to generate the {@link URI} for.
     * @param propertyKey the {@link PropertyKey} to generate the {@link URI} for.
     * @param op the operation to associate with the returned URI
     * @param modifiers any {@link Modifier modifiers} to associate with this {@link URI}.
     * @return a {@link URI} representing this specific property, including modifiers
     * @throws UnassociatedResourceException If this FunctionalEndpoint isn't associated with this
     *         technology.
     * @see #getRelativeUriForFunctionalEndpoint(FunctionalEndpoint, URI)
     */
    URI getNativeUriForProperty(FunctionalEndpoint fe, PropertyKey<?> propertyKey, Operation op,
                                Modifier ... modifiers) throws UnassociatedResourceException;

    /**
     * Convenience wrapper for
     * {@link #getNativeUriForProperty(FunctionalEndpoint, PropertyKey, Operation, Modifier...)}.
     *
     * This method is effectively the same as calling
     * {@link #getNativeUriForProperty(FunctionalEndpoint, PropertyKey, Operation, Modifier...)}
     * with {@link Operation#UNSPECIFIED} and no modifiers.
     *
     * @param fe the {@link FunctionalEndpoint} to generate the {@link URI} for.
     * @param propertyKey the {@link PropertyKey} to generate the {@link URI} for.
     * @return a {@link URI} representing this specific property, including modifiers
     * @throws UnassociatedResourceException If this FunctionalEndpoint isn't associated with this
     *         technology.
     * @see #getRelativeUriForFunctionalEndpoint(FunctionalEndpoint, URI)
     */
    default URI getNativeUriForProperty(FunctionalEndpoint fe, PropertyKey<?> propertyKey)
            throws UnassociatedResourceException {
        return getNativeUriForProperty(fe, propertyKey, Operation.UNSPECIFIED);
    }

    /**
     * Calculated the native URI that represents a {@link Section} on the given FunctionalEndpoint,
     * with optional modifiers.
     *
     * <p>The returned URI must be either an absolute URL (include schema and possibly authority) or
     * a absolute-path-only URL (No schema or authority). The format and scheme of the URI is native
     * (technology specific).
     *
     * <p>Note that the returned URI is relative to this device/technology. If you intend to
     * set this URI as a property value on a non-local FunctionalEndpoint, you must first
     * change the URI to be relative to that FunctionalEndpoint using
     * {@link #getRelativeUriForFunctionalEndpoint(FunctionalEndpoint, URI)}.
     *
     * @param fe the {@link FunctionalEndpoint} to generate the {@link URI} for.
     * @param section the {@link Section} to generate the {@link URI} for.
     * @param modifiers any {@link Modifier modifiers} to associate with this {@link URI}.
     * @return a {@link URI} representing this specific property, including modifiers
     * @throws UnassociatedResourceException If this {@link FunctionalEndpoint} isn't associated
     *         with this technology.
     * @see #getRelativeUriForFunctionalEndpoint(FunctionalEndpoint, URI)
     */
    URI getNativeUriForSection(FunctionalEndpoint fe, Section section,
                               Modifier ... modifiers) throws UnassociatedResourceException;

    /**
     * Converts the given {@link URI} that is relative to this Technology to be relative to the
     * given {@link FunctionalEndpoint}. The returned URI may then be set as a property value
     * on the given {@link FunctionalEndpoint}.
     *
     * <h3>Default Implementation Details</h3>
     *
     * <p>The default implementation of this method will return the URI unchanged in either
     * of the following two cases:
     *
     * <ul>
     *     <li>URI is absolute, including scheme component</li>
     *     <li>URI is a local absolute path AND {@link Technology#isHosted(FunctionalEndpoint)}
     *     isHosted(fe)} returns {@code true}</li>
     * </ul>
     *
     * <p>In the second case:
     *
     * <ul>
     *     <li>If the URI is not a local absolute path with no scheme, {@link UnknownResourceException} is thrown.</li>
     *     <li>If {@code isHosted(fe)} returns false, then {@link UnassociatedResourceException} is thrown.</li>
     * </ul>
     *
     * @param fe The FunctionalEndpoint to convert the URI to be relative to.
     * @param uri The URI to convert.
     * @return a URI that is relative to the given FunctionalEndpoint.
     * @throws UnassociatedResourceException if the given FunctionalEndpoint is not associated
     *         with this Technology.
     * @throws UnknownResourceException if the given URI can't be converted.
     */
    default URI getRelativeUriForFunctionalEndpoint(FunctionalEndpoint fe, URI uri)
            throws UnknownResourceException, UnassociatedResourceException {
        if (!uri.isAbsolute()) {
            if (uri.getPath() != null && uri.getPath().charAt(0) != '/') {
                throw new UnknownResourceException("Default implementation of " +
                        "getRelativeUriForFunctionalEndpoint can not convert relative local paths");
            }

            if (!isHosted(fe)) {
                throw new UnassociatedResourceException("Default implementation of " +
                        "getRelativeUriForFunctionalEndpoint can only handle hosted " +
                        "Functional Endpoints.");
            }

            return uri;
        }

        URI feUri = getNativeUriForFunctionalEndpoint(fe);

        try {
            if (Objects.equals(uri.getScheme(), feUri.getScheme())
                    && Objects.equals(uri.getAuthority(), feUri.getAuthority())) {
                return new URI(null, null,
                        uri.getPath(), uri.getQuery(), uri.getFragment());
            }

        } catch (URISyntaxException e) {
            // This shouldn't really happen.
            throw new TechnologyRuntimeException(e);
        }

        return uri;
    }

    /** Returns a new instance of a builder object for constructing DiscoveryTask objects. */
    DiscoveryBuilder createDiscoveryQueryBuilder();

    /**
     * Creates a new empty {@link Group} with a randomly-generated group identifier. The returned
     * group is NOT automatically hosted, which is required to add hosted functional endpoints to
     * the group. If you are only adding native functional endpoints, then there is no need to host
     * the group.
     *
     * <p>Note that some technologies may have additional methods for specifying more details about
     * group creation. For example, some technologies support the creation of groups that have
     * exclusive membership. This method will return a group that best matches a "normal" group that
     * has a few arbitrary limitations as possible.
     *
     * @return A {@link ListenableFuture} for retrieving the newly created group
     */
    default ListenableFuture<Group> createNewGroup() {
        return Futures.immediateFailedFuture(new GroupsNotSupportedException());
    }

    /**
     * Looks up a {@link Group} object based on a groupId. If one could not be found, a new one is
     * created with the given groupId. Note that, much like {@link #createNewGroup()}, if a new
     * group is created it is not automatically hosted.
     *
     * @param groupId the group identifier of the group to fetch
     * @return a future containing a {@link Group} with the given group identifier or null if this
     *     Technology does not support group creation with arbitrary groupId strings
     */
    default ListenableFuture<Group> fetchOrCreateGroupWithId(String groupId) {
        return Futures.immediateFuture(null);
    }

    /**
     * Looks up all of the hosted groups on this technology. A hosted group is a group that hosted
     * {@link FunctionalEndpoint}s can participate in. If a technology does not support hosting
     * functional endpoints, this method will return an empty collection.
     *
     * @return a collection of {@link Group} objects
     */
    default Collection<Group> copyHostedGroups() {
        return new LinkedList<>();
    }
}
