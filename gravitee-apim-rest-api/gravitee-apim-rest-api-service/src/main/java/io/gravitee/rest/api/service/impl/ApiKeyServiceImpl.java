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
package io.gravitee.rest.api.service.impl;

import static io.gravitee.repository.management.model.ApiKey.AuditEvent.*;
import static io.gravitee.repository.management.model.Audit.AuditProperties.*;

import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.ApiKeyRepository;
import io.gravitee.repository.management.api.search.ApiKeyCriteria;
import io.gravitee.repository.management.model.ApiKey;
import io.gravitee.repository.management.model.Audit;
import io.gravitee.rest.api.model.*;
import io.gravitee.rest.api.model.key.ApiKeyQuery;
import io.gravitee.rest.api.service.*;
import io.gravitee.rest.api.service.common.UuidString;
import io.gravitee.rest.api.service.exceptions.*;
import io.gravitee.rest.api.service.notification.ApiHook;
import io.gravitee.rest.api.service.notification.NotificationParamsBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class ApiKeyServiceImpl extends TransactionalService implements ApiKeyService {

    /**
     * Logger.
     */
    private final Logger LOGGER = LoggerFactory.getLogger(ApiKeyServiceImpl.class);

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private ApiKeyGenerator apiKeyGenerator;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private ApiService apiService;

    @Autowired
    private PlanService planService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private NotifierService notifierService;

    @Override
    public ApiKeyEntity generate(String subscription) {
        return generate(subscription, null);
    }

    @Override
    public ApiKeyEntity generate(String subscription, String customApiKey) {
        try {
            LOGGER.debug("Generate an API Key for subscription {}", subscription);

            ApiKey apiKey = generateForSubscription(subscription, customApiKey);
            apiKey = apiKeyRepository.create(apiKey);

            //TODO: Send a notification to the application owner

            // Audit
            final PlanEntity plan = planService.findById(apiKey.getPlan());

            Map<Audit.AuditProperties, String> properties = new LinkedHashMap<>();
            properties.put(API_KEY, apiKey.getKey());
            properties.put(API, plan.getApi());
            properties.put(APPLICATION, apiKey.getApplication());

            auditService.createApiAuditLog(plan.getApi(), properties, APIKEY_CREATED, apiKey.getCreatedAt(), null, apiKey);
            return convert(apiKey);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to generate an API Key for {} - {}", subscription, ex);
            throw new TechnicalManagementException(
                String.format("An error occurs while trying to generate an API Key for %s", subscription),
                ex
            );
        }
    }

    @Override
    public ApiKeyEntity renew(String subscription) {
        return renew(subscription, null);
    }

    @Override
    public ApiKeyEntity renew(String subscription, String customApiKey) {
        try {
            LOGGER.debug("Renew API Key for subscription {}", subscription);

            ApiKey newApiKey = generateForSubscription(subscription, customApiKey);
            newApiKey = apiKeyRepository.create(newApiKey);

            Instant expirationInst = newApiKey.getCreatedAt().toInstant().plus(Duration.ofHours(2));
            Date expirationDate = Date.from(expirationInst);

            // Previously generated keys should be set as revoked
            // Get previously generated keys to set their expiration date
            Set<ApiKey> oldKeys = apiKeyRepository.findBySubscription(subscription);
            for (ApiKey oldKey : oldKeys) {
                if (!oldKey.equals(newApiKey) && !convert(oldKey).isExpired()) {
                    setExpiration(expirationDate, oldKey);
                }
            }

            // Audit
            final PlanEntity plan = planService.findById(newApiKey.getPlan());

            Map<Audit.AuditProperties, String> properties = new LinkedHashMap<>();
            properties.put(API_KEY, newApiKey.getKey());
            properties.put(API, plan.getApi());
            properties.put(APPLICATION, newApiKey.getApplication());

            auditService.createApiAuditLog(plan.getApi(), properties, APIKEY_RENEWED, newApiKey.getCreatedAt(), null, newApiKey);

            // Notification
            final ApplicationEntity application = applicationService.findById(newApiKey.getApplication());
            final ApiModelEntity api = apiService.findByIdForTemplates(plan.getApi());
            final PrimaryOwnerEntity owner = application.getPrimaryOwner();
            final Map<String, Object> params = new NotificationParamsBuilder()
                .application(application)
                .plan(plan)
                .api(api)
                .owner(owner)
                .apikey(newApiKey)
                .build();
            notifierService.trigger(ApiHook.APIKEY_RENEWED, plan.getApi(), params);

            return convert(newApiKey);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to renew an API Key for {}", subscription, ex);
            throw new TechnicalManagementException(
                String.format("An error occurs while trying to renew an API Key for %s", subscription),
                ex
            );
        }
    }

    /**
     * Generate an {@link ApiKey} from a subscription
     *
     * @param subscription
     * @return An Api Key
     */
    private ApiKey generateForSubscription(String subscription) {
        return generateForSubscription(subscription, null);
    }

    /**
     * Generate an {@link ApiKey} from a subscription. If no custom API key, then generate a new one.
     *
     * @param subscription
     * @param customApiKey
     * @return An Api Key
     */
    private ApiKey generateForSubscription(String subscription, String customApiKey) {
        SubscriptionEntity subscriptionEntity = subscriptionService.findById(subscription);

        if (customApiKey != null && canCreate(customApiKey, subscriptionEntity.getApi(), subscriptionEntity.getApplication())) {
            throw new ApiKeyAlreadyExistingException();
        }

        Date now = new Date();
        if (subscriptionEntity.getEndingAt() != null && subscriptionEntity.getEndingAt().before(now)) {
            throw new SubscriptionClosedException(subscription);
        }

        ApiKey apiKey = new ApiKey();
        apiKey.setId(UuidString.generateRandom());
        apiKey.setSubscription(subscription);
        apiKey.setApplication(subscriptionEntity.getApplication());
        apiKey.setPlan(subscriptionEntity.getPlan());
        apiKey.setCreatedAt(new Date());
        apiKey.setUpdatedAt(apiKey.getCreatedAt());
        apiKey.setKey(customApiKey != null ? customApiKey : apiKeyGenerator.generate());
        apiKey.setApi(subscriptionEntity.getApi());

        // By default, the API Key will expire when subscription is closed
        apiKey.setExpireAt(subscriptionEntity.getEndingAt());

        return apiKey;
    }

    @Override
    public void revoke(ApiKeyEntity apiKeyEntity, boolean notify) {
        revoke(apiKeyEntity.getKey(), apiKeyEntity.getApi(), notify);
    }

    @Override
    public void revoke(String apiKey, String apiId, boolean notify) {
        try {
            LOGGER.debug("Revoke API Key {} for API {}", apiKey, apiId);
            ApiKey key = apiKeyRepository.findByKeyAndApi(apiKey, apiId).orElseThrow(() -> new ApiKeyNotFoundException());

            checkApiKeyExpired(key);

            ApiKey previousApiKey = new ApiKey(key);
            key.setRevoked(true);
            key.setUpdatedAt(new Date());
            key.setRevokedAt(key.getUpdatedAt());

            apiKeyRepository.update(key);

            final PlanEntity plan = planService.findById(key.getPlan());

            // Audit
            Map<Audit.AuditProperties, String> properties = new LinkedHashMap<>();
            properties.put(API_KEY, key.getKey());
            properties.put(API, plan.getApi());
            properties.put(APPLICATION, key.getApplication());

            auditService.createApiAuditLog(plan.getApi(), properties, APIKEY_REVOKED, key.getUpdatedAt(), previousApiKey, key);

            // notify
            if (notify) {
                final ApplicationEntity application = applicationService.findById(key.getApplication());
                final ApiModelEntity api = apiService.findByIdForTemplates(plan.getApi());
                final PrimaryOwnerEntity owner = application.getPrimaryOwner();
                final Map<String, Object> params = new NotificationParamsBuilder()
                    .application(application)
                    .plan(plan)
                    .api(api)
                    .owner(owner)
                    .apikey(key)
                    .build();
                notifierService.trigger(ApiHook.APIKEY_REVOKED, api.getId(), params);
            }
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to revoke a key {}", apiKey, ex);
            throw new TechnicalManagementException("An error occurs while trying to revoke a key " + apiKey, ex);
        }
    }

    @Override
    public ApiKeyEntity reactivate(ApiKeyEntity apiKeyEntity) {
        return reactivate(apiKeyEntity.getKey(), apiKeyEntity.getApi());
    }

    @Override
    public ApiKeyEntity reactivate(String apiKey, String apiId) {
        try {
            LOGGER.debug("Reactivate API Key {} for API {}", apiKey, apiId);
            ApiKey key = apiKeyRepository.findByKeyAndApi(apiKey, apiId).orElseThrow(() -> new ApiKeyNotFoundException());

            if (!key.isRevoked() && !convert(key).isExpired()) {
                throw new ApiKeyAlreadyActivatedException();
            }

            // Get the subscription to get ending date and set key expiration date.
            SubscriptionEntity subscription = subscriptionService.findById(key.getSubscription());
            if (subscription.getStatus() != SubscriptionStatus.PAUSED && subscription.getStatus() != SubscriptionStatus.ACCEPTED) {
                throw new SubscriptionNotActiveException(subscription);
            }

            ApiKey previousApiKey = new ApiKey(key);
            key.setRevoked(false);
            key.setUpdatedAt(new Date());
            key.setRevokedAt(null);
            key.setExpireAt(subscription.getEndingAt());

            ApiKey updated = apiKeyRepository.update(key);

            // Audit
            Map<Audit.AuditProperties, String> properties = new LinkedHashMap<>();
            properties.put(API_KEY, key.getKey());
            properties.put(API, subscription.getApi());
            properties.put(APPLICATION, key.getApplication());

            auditService.createApiAuditLog(subscription.getApi(), properties, APIKEY_REACTIVATED, key.getUpdatedAt(), previousApiKey, key);

            return convert(updated);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to reactivate a key {}", apiKey, ex);
            throw new TechnicalManagementException("An error occurs while trying to reactivate a key " + apiKey, ex);
        }
    }

    private void checkApiKeyExpired(ApiKey key) {
        if (key.isRevoked() || convert(key).isExpired()) {
            throw new ApiKeyAlreadyExpiredException();
        }
    }

    @Override
    public List<ApiKeyEntity> findByKey(String apiKey) {
        try {
            LOGGER.debug("Find API Keys for apiKey {}", apiKey);
            return apiKeyRepository.findByKey(apiKey).stream().map(ApiKeyServiceImpl::convert).collect(Collectors.toList());
        } catch (TechnicalException e) {
            LOGGER.error("An error occurs while finding API keys with key {}", apiKey, e);
            throw new TechnicalManagementException(String.format("An error occurs while finding API keys with key %s", apiKey), e);
        }
    }

    @Override
    public List<ApiKeyEntity> findBySubscription(String subscription) {
        try {
            LOGGER.debug("Find API Keys for subscription {}", subscription);

            SubscriptionEntity subscriptionEntity = subscriptionService.findById(subscription);
            Set<ApiKey> keys = apiKeyRepository.findBySubscription(subscriptionEntity.getId());
            return keys
                .stream()
                .map(ApiKeyServiceImpl::convert)
                .sorted((o1, o2) -> o2.getCreatedAt().compareTo(o1.getCreatedAt()))
                .collect(Collectors.toList());
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while finding API keys for subscription {}", subscription, ex);
            throw new TechnicalManagementException(
                String.format("An error occurs while finding API keys for subscription %s", subscription),
                ex
            );
        }
    }

    @Override
    public ApiKeyEntity findByKeyAndApi(String apiKey, String apiId) {
        try {
            LOGGER.debug("Find an API Key by key {} and API {}", apiKey, apiId);
            ApiKey key = apiKeyRepository.findByKeyAndApi(apiKey, apiId).orElseThrow(() -> new ApiKeyNotFoundException());
            return convert(key);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find an API Key by key {} for API {}", apiKey, apiId, ex);
            throw new TechnicalManagementException(
                String.format("An error occurs while trying to find an API Key by key %s for API %s", apiKey, apiId),
                ex
            );
        }
    }

    @Override
    public ApiKeyEntity findByKeyAndSubscription(String apiKey, String subscriptionId) {
        try {
            LOGGER.debug("Find an API Key by key {} and subscription {}", apiKey, subscriptionId);
            ApiKey key = apiKeyRepository.findByKeyAndSubscription(apiKey, subscriptionId).orElseThrow(() -> new ApiKeyNotFoundException());
            return convert(key);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find an API Key by key {} for subscriptionId {}", apiKey, subscriptionId, ex);
            throw new TechnicalManagementException(
                String.format("An error occurs while trying to find an API Key by key %s for subscriptionId %s", apiKey, subscriptionId),
                ex
            );
        }
    }

    @Override
    public ApiKeyEntity update(ApiKeyEntity apiKeyEntity) {
        try {
            LOGGER.debug("Update API Key {}", apiKeyEntity.getKey());
            ApiKey key = apiKeyRepository
                .findByKeyAndApi(apiKeyEntity.getKey(), apiKeyEntity.getApi())
                .orElseThrow(() -> new ApiKeyNotFoundException());

            checkApiKeyExpired(key);

            key.setPaused(apiKeyEntity.isPaused());
            key.setPlan(apiKeyEntity.getPlan());
            if (apiKeyEntity.getExpireAt() != null) {
                setExpiration(apiKeyEntity.getExpireAt(), key);
            } else {
                key.setUpdatedAt(new Date());
                apiKeyRepository.update(key);
            }

            return convert(key);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while updating an API Key {}", apiKeyEntity.getKey(), ex);
            throw new TechnicalManagementException(
                String.format("An error occurs while updating an API Key %s", apiKeyEntity.getKey()),
                ex
            );
        }
    }

    @Override
    public ApiKeyEntity updateDaysToExpirationOnLastNotification(ApiKeyEntity apiKeyEntity, Integer value) {
        return updateDaysToExpirationOnLastNotification(apiKeyEntity.getKey(), apiKeyEntity.getApi(), value);
    }

    @Override
    public ApiKeyEntity updateDaysToExpirationOnLastNotification(String apiKey, String apiId, Integer value) {
        try {
            return apiKeyRepository
                .findByKeyAndApi(apiKey, apiId)
                .map(
                    dbApiKey -> {
                        dbApiKey.setDaysToExpirationOnLastNotification(value);
                        try {
                            return apiKeyRepository.update(dbApiKey);
                        } catch (TechnicalException ex) {
                            LOGGER.error("An error occurs while trying to update dbApiKey {}", apiKey, ex);
                            throw new TechnicalManagementException(
                                String.format("An error occurs while trying to update dbApiKey %s", apiKey),
                                ex
                            );
                        }
                    }
                )
                .map(ApiKeyServiceImpl::convert)
                .orElseThrow(ApiKeyNotFoundException::new);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to update apiKey {}", apiKey, ex);
            throw new TechnicalManagementException(String.format("An error occurs while trying to update apiKey %s", apiKey), ex);
        }
    }

    @Override
    public boolean canCreate(String apiKey, String apiId, String applicationId) {
        LOGGER.debug("Check if an API Key can be create with key {}, for api {} and application {}", apiKey, apiId, applicationId);
        try {
            return apiKeyRepository
                .findByKey(apiKey)
                .stream()
                .noneMatch(
                    existingKey ->
                        !existingKey.getApplication().equals(applicationId) ||
                        (existingKey.getApplication().equals(applicationId) && existingKey.getApi().equals(apiId))
                );
        } catch (TechnicalException ex) {
            LOGGER.error(
                "An error occurs while checking if API Key {} can be created for api {} and application {}",
                apiKey,
                apiId,
                applicationId,
                ex
            );
            throw new TechnicalManagementException(
                String.format(
                    "An error occurs while checking if API Key %s can be created for api %s and application %s",
                    apiKey,
                    apiId,
                    applicationId
                ),
                ex
            );
        }
    }

    @Override
    public Collection<ApiKeyEntity> search(ApiKeyQuery query) {
        try {
            LOGGER.debug("Search api keys {}", query);

            ApiKeyCriteria.Builder builder = toApiKeyCriteriaBuilder(query);

            return apiKeyRepository.findByCriteria(builder.build()).stream().map(ApiKeyServiceImpl::convert).collect(Collectors.toList());
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to search api keys: {}", query, ex);
            throw new TechnicalManagementException(String.format("An error occurs while trying to search api keys: {}", query), ex);
        }
    }

    @Override
    public void delete(String apiKey) {
        /*
        try {
            LOGGER.debug("Delete API Key {}", apiKey);
            Optional<ApiKey> optKey = apiKeyRepository.de(apiKey);
            if (!optKey.isPresent()) {
                throw new ApiKeyNotFoundException();
            }

            ApiKey key = optKey.get();

            setExpiration(apiKeyEntity.getExpireAt(), key);

            return convert(key);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to update a key {}", apiKey, ex);
            throw new TechnicalManagementException("An error occurs while trying to update a key " + apiKey, ex);
        }
        */
    }

    private void setExpiration(Date expirationDate, ApiKey key) throws TechnicalException {
        final Date now = new Date();

        if (now.after(expirationDate)) {
            expirationDate = now;
        }

        key.setUpdatedAt(now);
        if (!key.isRevoked()) {
            //the expired date must be <= than the subscription end date
            SubscriptionEntity subscription = subscriptionService.findById(key.getSubscription());
            if (
                subscription.getEndingAt() != null && (expirationDate == null || subscription.getEndingAt().compareTo(expirationDate) < 0)
            ) {
                expirationDate = subscription.getEndingAt();
            }

            ApiKey oldkey = new ApiKey(key);
            key.setExpireAt(expirationDate);
            key.setDaysToExpirationOnLastNotification(null);
            apiKeyRepository.update(key);

            //notify
            final ApplicationEntity application = applicationService.findById(key.getApplication());
            final PlanEntity plan = planService.findById(key.getPlan());
            final ApiModelEntity api = apiService.findByIdForTemplates(plan.getApi());
            final PrimaryOwnerEntity owner = application.getPrimaryOwner();

            NotificationParamsBuilder paramsBuilder = new NotificationParamsBuilder();
            paramsBuilder.api(api).application(application).apikey(key).plan(plan).owner(owner);
            if (key.getExpireAt() != null && now.before(key.getExpireAt())) {
                paramsBuilder.expirationDate(key.getExpireAt());
            }

            final Map<String, Object> params = paramsBuilder.build();

            notifierService.trigger(ApiHook.APIKEY_EXPIRED, api.getId(), params);

            // Audit
            Map<Audit.AuditProperties, String> properties = new LinkedHashMap<>();
            properties.put(API_KEY, key.getKey());
            properties.put(API, api.getId());
            properties.put(APPLICATION, application.getId());

            auditService.createApiAuditLog(plan.getApi(), properties, APIKEY_EXPIRED, key.getUpdatedAt(), oldkey, key);
        } else {
            apiKeyRepository.update(key);
        }
    }

    private static ApiKeyEntity convert(ApiKey apiKey) {
        ApiKeyEntity apiKeyEntity = new ApiKeyEntity();

        apiKeyEntity.setId(apiKey.getId());
        apiKeyEntity.setKey(apiKey.getKey());
        apiKeyEntity.setCreatedAt(apiKey.getCreatedAt());
        apiKeyEntity.setExpireAt(apiKey.getExpireAt());
        apiKeyEntity.setExpired(apiKey.getExpireAt() != null && new Date().after(apiKey.getExpireAt()));
        apiKeyEntity.setRevoked(apiKey.isRevoked());
        apiKeyEntity.setRevokedAt(apiKey.getRevokedAt());
        apiKeyEntity.setUpdatedAt(apiKey.getUpdatedAt());
        apiKeyEntity.setSubscription(apiKey.getSubscription());
        apiKeyEntity.setApplication(apiKey.getApplication());
        apiKeyEntity.setPlan(apiKey.getPlan());
        apiKeyEntity.setDaysToExpirationOnLastNotification(apiKey.getDaysToExpirationOnLastNotification());
        apiKeyEntity.setApi(apiKey.getApi());

        return apiKeyEntity;
    }

    private ApiKeyCriteria.Builder toApiKeyCriteriaBuilder(ApiKeyQuery query) {
        return new ApiKeyCriteria.Builder()
            .includeRevoked(query.isIncludeRevoked())
            .plans(query.getPlans())
            .from(query.getFrom())
            .to(query.getTo())
            .expireAfter(query.getExpireAfter())
            .expireBefore(query.getExpireBefore());
    }
}
