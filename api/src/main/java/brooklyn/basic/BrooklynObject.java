/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.basic;

import java.util.Set;

import javax.annotation.Nonnull;

import brooklyn.entity.trait.Configurable;
import brooklyn.entity.trait.Identifiable;

import com.google.common.collect.ImmutableMap;

/**
 * Super-type of entity, location, policy and enricher.
 */
public interface BrooklynObject extends Identifiable, Configurable {
    
    /**
     * A display name; recommended to be a concise single-line description.
     */
    String getDisplayName();

    /**
     * The catalog item ID this object was loaded from.
     * <p>
     * This can be used to understand the appropriate classloading context,
     * such as for versioning purposes, as well as meta-information such as 
     * branding (maybe you can even get an icon) and 
     * potentially things like resource lifecycle (if a software version is being sunsetted).
     * <p>
     * In some cases this may be set heuristically from context and so may not be accurate.
     * Callers can set an explicit catalog item ID if inferencing is not correct.
     */
    String getCatalogItemId();
    
    /** 
     * Tags are arbitrary objects which can be attached to an entity for subsequent reference.
     * They must not be null (as {@link ImmutableMap} may be used under the covers; also there is little point!);
     * and they should be amenable to our persistence (on-disk serialization) and our JSON serialization in the REST API.
     */
    TagSupport tags();
    
    public interface TagSupport {
        /**
         * @return An immutable copy of the set of tags on this entity. 
         * Note {@link #containsTag(Object)} will be more efficient,
         * and {@link #addTag(Object)} and {@link #removeTag(Object)} will not work on the returned set.
         */
        @Nonnull Set<Object> getTags();
        
        boolean containsTag(@Nonnull Object tag);
        
        boolean addTag(@Nonnull Object tag);
        
        boolean addTags(@Nonnull Iterable<?> tags);
        
        boolean removeTag(@Nonnull Object tag);
    }
}
