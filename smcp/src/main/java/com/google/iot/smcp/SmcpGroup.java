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
package com.google.iot.smcp;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.iot.coap.Client;
import com.google.iot.coap.Coap;
import com.google.iot.coap.UnsupportedSchemeException;
import com.google.iot.m2m.base.*;
import com.google.iot.m2m.local.LocalThing;
import com.google.iot.m2m.trait.BaseTrait;
import java.util.*;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.Nullable;

final class SmcpGroup extends SmcpThing implements Group, PersistentStateInterface {

    private final String mGroupId;

    /* Sync to `this` */
    private final HashSet<SmcpThing> mNativeMembers = new HashSet<>();

    @SuppressWarnings({"CanBeFinal", "FieldCanBeLocal"})
    private boolean mCanAdministerGroup = true;

    @SuppressWarnings({"CanBeFinal", "FieldCanBeLocal"})
    private boolean mCanUseGroup = true;

    /**
     * Group containing all of the hosted things. Note that these things
     * aren't necessarily "local", just that they are not native to this technology instance (which
     * usually means they are subclasses of {@link LocalThing}).
     */
    final Group mLocalGroup;

    private static Client createClient(SmcpTechnology technology, String groupId) {
        try {
            return new Client(
                    technology.mLocalEndpointManager,
                    Coap.SCHEME_UDP + "://" + Coap.ALL_NODES_MCAST_HOSTNAME + "/g/" + groupId);
        } catch (UnsupportedSchemeException e) {
            // Should not happen.
            throw new AssertionError(e);
        }
    }
    SmcpGroup(SmcpTechnology technology, String groupId) {
        // TODO: Use group-specific multicast addresses.
        super(createClient(technology, groupId), technology);

        mGroupId = groupId;
        mLocalGroup = mTechnology.mLocalTechnology.findOrCreateGroupWithId(groupId);
    }

    Executor getExecutor() {
        return mTechnology.getExecutor();
    }

    /** Called by {@link SmcpTechnology} when this group is unhosted. */
    void onUnhosted() {
        mLocalGroup.getTechnology().unhost(mLocalGroup);
    }

    /** Called by {@link SmcpTechnology} when this group is hosted. */
    void onHosted() {
        try {
            mLocalGroup.getTechnology().host(mLocalGroup);
        } catch (UnacceptableThingException e) {
            throw new SmcpRuntimeException(e);
        }
    }

    @Override
    public String getGroupId() {
        return mGroupId;
    }

    @Override
    public SmcpTechnology getTechnology() {
        return mTechnology;
    }

    @Override
    public boolean hasLocalMembers() {
        return mLocalGroup.hasLocalMembers();
    }

    @Override
    public boolean canAdministerGroup() {
        return mCanAdministerGroup;
    }

    @Override
    public boolean canUseGroup() {
        return mCanUseGroup;
    }

    @Override
    public boolean isReliable() {
        return false;
    }

    @Override
    public ListenableFuture<Set<Thing>> fetchMembers() {
        if (mTechnology.isHosted(this)) {
            ListenableFuture<Set<Thing>> localFuture = mLocalGroup.fetchMembers();

            ListenableFutureTask<Set<Thing>> future =
                    ListenableFutureTask.create(
                            () -> {
                                synchronized (SmcpGroup.this) {
                                    Set<Thing> ret = new HashSet<>(mNativeMembers);
                                    ret.addAll(localFuture.get());
                                    return ret;
                                }
                            });

            future.addListener(
                    () -> {
                        if (future.isCancelled()) {
                            localFuture.cancel(true);
                        }
                    },
                    getExecutor());

            localFuture.addListener(future, getExecutor());

            return future;
        }
        return Futures.immediateFuture(new HashSet<>(mNativeMembers));
    }

    @Override
    public ListenableFuture<Void> addMember(Thing fe) {
        if (fe instanceof SmcpGroup && ((SmcpGroup) fe).getTechnology() == mTechnology) {
            return Futures.immediateFailedFuture(
                    new UnacceptableThingException(
                            "Cannot add a native group as a member"));
        }

        if (!mTechnology.isAssociatedWith(fe)) {
            return Futures.immediateFailedFuture(
                    new UnacceptableThingException(
                            "Thing is not yet associated with this technology"));
        }

        if (mTechnology.isHosted(fe)) {
            return mLocalGroup.addMember(fe);
        }

        if (mTechnology.isNative(fe) && fe instanceof SmcpThing) {
            /* This is a native member, we need to interact with this FE to add it to the group */

            ListenableFutureTask<Void> future =
                    ListenableFutureTask.create(
                            () -> {
                                // TODO: We must manually add the remote FE to this group,
                                //       adding the group to that device if necessary!
                                synchronized (SmcpGroup.this) {
                                    mNativeMembers.add((SmcpThing) fe);
                                }
                            },
                            null);

            getExecutor().execute(future);

            return future;
        }

        return Futures.immediateFailedFuture(
                new UnacceptableThingException(
                        "This group must itself be hosted before adding hosted things"));
    }

    @Override
    public ListenableFuture<Void> removeMember(Thing fe) {
        synchronized (SmcpGroup.this) {
            @SuppressWarnings("unchecked")
            SmcpThing smcpFe = (SmcpThing) fe;
            mNativeMembers.remove(smcpFe);
        }
        return mLocalGroup.removeMember(fe);
    }

    private <T> ListenableFuture<T> chainCancelation(
            ListenableFuture<?> nativeFuture, ListenableFuture<T> hostedFuture) {
        hostedFuture.addListener(
                () -> {
                    if (hostedFuture.isCancelled()) {
                        nativeFuture.cancel(true);
                    }
                },
                /* This executes fast, so it's fine to use Runnable::run */
                Runnable::run);

        return hostedFuture;
    }

    @Override
    public <T> ListenableFuture<?> setProperty(PropertyKey<T> key, @Nullable T value, Modifier ... modifiers) {
        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            ListenableFuture<?> nativeFuture = super.setProperty(key, value, modifiers);
            ListenableFuture<?> hostedFuture = mLocalGroup.setProperty(key, value, modifiers);
            return chainCancelation(nativeFuture, hostedFuture);
        }
        return super.setProperty(key, value, modifiers);
    }

    @Override
    public <T extends Number> ListenableFuture<?> incrementProperty(PropertyKey<T> key, T value, Modifier ... modifiers) {
        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            ListenableFuture<?> nativeFuture = super.incrementProperty(key, value, modifiers);
            ListenableFuture<?> hostedFuture = mLocalGroup.incrementProperty(key, value, modifiers);
            return chainCancelation(nativeFuture, hostedFuture);
        }
        return super.incrementProperty(key, value, modifiers);
    }

    @Override
    public <T> ListenableFuture<?> insertValueIntoProperty(PropertyKey<T[]> key, T value, Modifier ... modifiers) {
        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            return chainCancelation(
                    super.insertValueIntoProperty(key, value, modifiers),
                    mLocalGroup.insertValueIntoProperty(key, value, modifiers));
        }
        return super.insertValueIntoProperty(key, value, modifiers);
    }

    @Override
    public <T> ListenableFuture<?> removeValueFromProperty(PropertyKey<T[]> key, T value, Modifier ... modifiers) {
        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            return chainCancelation(
                    super.removeValueFromProperty(key, value, modifiers),
                    mLocalGroup.removeValueFromProperty(key, value, modifiers));
        }
        return super.removeValueFromProperty(key, value, modifiers);
    }

    @Override
    public ListenableFuture<?> toggleProperty(PropertyKey<Boolean> key, Modifier ... modifiers) {
        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            return chainCancelation(super.toggleProperty(key, modifiers), mLocalGroup.toggleProperty(key, modifiers));
        }
        return super.toggleProperty(key, modifiers);
    }

    @Override
    public <T> ListenableFuture<T> fetchProperty(PropertyKey<T> key, Modifier ... modifiers) {
        if (BaseTrait.META_UID.equals(key)) {
            // We always know the UID, since it is the group id.
            return Futures.immediateFuture(key.cast(mGroupId));
        }

        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            return mLocalGroup.fetchProperty(key, modifiers);
        }

        return super.fetchProperty(key, modifiers);
    }

    @Override
    public ListenableFuture<Map<String, Object>> fetchSection(Section section, Modifier ... modifiers) {
        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            return mLocalGroup.fetchSection(section, modifiers);
        }
        return super.fetchSection(section, modifiers);
    }

    @Nullable
    @Override
    public <T> T getCachedProperty(PropertyKey<T> key) {
        if (BaseTrait.META_UID.equals(key)) {
            // We always know the UID, since it is the group id.
            return key.cast(mGroupId);
        }

        if (mTechnology.isHosted(this)) {
            return mLocalGroup.getCachedProperty(key);
        }

        return super.getCachedProperty(key);
    }

    @Override
    public Map<String, Object> copyCachedSection(Section section) {
        final Map<String, Object> ret = super.copyCachedSection(section);

        if (mTechnology.isHosted(this)) {
            switch (section) {
                case STATE:
                case CONFIG:
                    if (mLocalGroup.hasLocalMembers()) {
                        ret.putAll(mLocalGroup.copyCachedSection(section));
                    }
                    break;

                case METADATA:
                    ret.putAll(mLocalGroup.copyCachedSection(section));
                    BaseTrait.META_UID.putInMap(ret, mGroupId);
                    break;
            }
        }

        return ret;
    }

    @Override
    public ListenableFuture<?> applyProperties(Map<String, Object> properties) {
        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            return chainCancelation(
                    super.applyProperties(properties), mLocalGroup.applyProperties(properties));
        }
        return super.applyProperties(properties);
    }

    @Override
    public <T> void registerPropertyListener(
            Executor executor, PropertyKey<T> key, PropertyListener<T> listener) {
        mLocalGroup.registerPropertyListener(executor, key, listener);
    }

    @Override
    public <T> void unregisterPropertyListener(PropertyKey<T> key, PropertyListener<T> listener) {
        mLocalGroup.unregisterPropertyListener(key, listener);
    }

    @Override
    public void registerSectionListener(Executor executor, Section section, SectionListener listener) {
        mLocalGroup.registerSectionListener(executor, section, listener);
    }

    @Override
    public void unregisterSectionListener(SectionListener listener) {
        mLocalGroup.unregisterSectionListener(listener);
    }

    @Override
    public ListenableFuture<Set<PropertyKey<?>>> fetchSupportedPropertyKeys() {
        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            return mLocalGroup.fetchSupportedPropertyKeys();
        }
        return super.fetchSupportedPropertyKeys();
    }

    @Override
    public <T> ListenableFuture<T> invokeMethod(
            MethodKey<T> methodKey, Map<String, Object> arguments) {
        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            return chainCancelation(
                    super.invokeMethod(methodKey, arguments),
                    mLocalGroup.invokeMethod(methodKey, arguments));
        }
        return super.invokeMethod(methodKey, arguments);
    }

    @Override
    public ListenableFuture<Collection<Thing>> fetchChildrenForTrait(
            String traitShortId) {
        if (mTechnology.isHosted(this) && mLocalGroup.hasLocalMembers()) {
            return mLocalGroup.fetchChildrenForTrait(traitShortId);
        }
        return super.fetchChildrenForTrait(traitShortId);
    }

    @Override
    public Map<String, Object> copyPersistentState() {
        // We don't actually save anything yet, so we return an empty map.
        return new HashMap<>();
    }

    @Override
    public void initWithPersistentState(@Nullable Map<String, Object> persistentState) {
        // We don't have anything to recall at the moment, so we do nothing here.
    }

    @Override
    public void setPersistentStateListener(@Nullable PersistentStateListener listener) {
        // We don't actually save anything yet, so we do nothing here.
    }
}
