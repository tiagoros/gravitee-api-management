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
package io.gravitee.repository.mongodb.management.internal.api;

import java.util.List;

import io.gravitee.repository.mongodb.management.internal.model.CategoryMongo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * @author Azize ELAMRANI (azize.elamrani at graviteesource.com)
 * @author GraviteeSource Team
 */
@Repository
public interface CategoryMongoRepository extends MongoRepository<CategoryMongo, String> {

    @Query("{ 'environmentId': ?0 }")
    List<CategoryMongo> findByEnvironmentId(String environmentId);

    @Query("{ 'environmentId': ?1, 'key': ?0 }")
    Optional<CategoryMongo> findByKeyAndEnvironment(String key, String environment);
}


