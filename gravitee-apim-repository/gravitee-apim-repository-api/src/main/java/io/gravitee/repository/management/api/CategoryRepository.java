/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.repository.management.api;

import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.model.Category;
import java.util.Optional;
import java.util.Set;

/**
 * @author Azize ELAMRANI (azize.elamrani at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author Florent CHAMFROY (florent.chamfroy at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface CategoryRepository {
    Optional<Category> findById(String id) throws TechnicalException;

    Category create(Category item) throws TechnicalException;

    Category update(Category item) throws TechnicalException;

    void delete(String id) throws TechnicalException;

    Set<Category> findAll() throws TechnicalException;

    Optional<Category> findByKey(String key, String environment) throws TechnicalException;

    Set<Category> findByPage(String page) throws TechnicalException;

    Set<Category> findAllByEnvironment(String environmentId) throws TechnicalException;
}
