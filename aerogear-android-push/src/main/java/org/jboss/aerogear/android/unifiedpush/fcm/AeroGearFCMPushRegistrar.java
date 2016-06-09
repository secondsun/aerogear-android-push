/**
 * JBoss, Home of Professional Open Source
 * Copyright Red Hat, Inc., and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.aerogear.android.unifiedpush.fcm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Base64;
import android.util.Log;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.net.HttpURLConnection;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.messaging.FirebaseMessaging;
import org.jboss.aerogear.android.core.Callback;
import org.jboss.aerogear.android.core.Provider;
import org.jboss.aerogear.android.pipe.http.HttpException;
import org.jboss.aerogear.android.pipe.http.HttpProvider;
import org.jboss.aerogear.android.pipe.http.HttpRestProvider;
import org.jboss.aerogear.android.pipe.util.UrlUtils;
import org.jboss.aerogear.android.unifiedpush.PushRegistrar;
import org.jboss.aerogear.android.unifiedpush.metrics.MetricsSender;
import org.jboss.aerogear.android.unifiedpush.metrics.UnifiedPushMetricsMessage;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

public class AeroGearFCMPushRegistrar implements PushRegistrar, MetricsSender<UnifiedPushMetricsMessage> {

    private final static String BASIC_HEADER = "Authorization";
    private final static String AUTHORIZATION_METHOD = "Basic";

    private static final String LEGACY_PROPERTY_REG_ID = "registration_id";

    private static final Integer TIMEOUT = 30000;// 30 seconds
    private static final String TAG = AeroGearFCMPushRegistrar.class.getSimpleName();
    /**
     * This pattern is used by {@link UnifiedPushInstanceIDListenerService} to
     * recognize keys which are saved by this class in the event that
     * registration tokens are refreshed by Google.
     *
     */
    static final String REGISTRAR_PREFERENCE_PATTERN = "org.jboss.aerogear.android.unifiedpush.gcm.AeroGearGCMPushRegistrar:.+";
    /**
     * This template creates a key used by the registrar to save push data to
     * SharedPreferences. This information will be fetched by
     * {@link UnifiedPushInstanceIDListenerService} in the event registration
     * tokens are reloaded.
     */
    static final String REGISTRAR_PREFERENCE_TEMPLATE = "org.jboss.aerogear.android.unifiedpush.gcm.AeroGearGCMPushRegistrar:%s";

    private static final String registryDeviceEndpoint = "/rest/registry/device";
    private static final String metricsEndpoint = "/rest/registry/device/pushMessage";

    private static final String DEVICE_ALREADY_UNREGISTERED = "Seems this device was already unregistered";

    private final String senderId;

    private FirebaseInstanceId instanceId;
    private URL deviceRegistryURL;
    private URL metricsURL;
    private String deviceToken = "";
    private final String secret;
    private final String variantId;
    private final String deviceType;
    private final String alias;
    private final String operatingSystem;
    private final String osVersion;
    private final ArrayList<String> categories;

    private Provider<HttpProvider> httpProviderProvider = new Provider<HttpProvider>() {

        @Override
        public HttpProvider get(Object... in) {
            return new HttpRestProvider((URL) in[0], (Integer) in[1]);
        }
    };

    
    private Provider<FirebaseInstanceId> firebaseInstanceIdProvider = new Provider<FirebaseInstanceId>() {

        @Override
        public FirebaseInstanceId get(Object... context) {
            return FirebaseInstanceId.getInstance();
        }
    };
    
    private Provider<FirebaseMessaging> firebaseMessagingProvider = new Provider<FirebaseMessaging>() {

        @Override
        public FirebaseMessaging get(Object... context) {
            return FirebaseMessaging.getInstance();
        }
    };

    private Provider<SharedPreferences> preferenceProvider = new FCMSharedPreferenceProvider();

    public AeroGearFCMPushRegistrar(UnifiedPushConfig config) {
        this.senderId = config.getSenderId();
        this.deviceToken = config.getDeviceToken();
        this.variantId = config.getVariantID();
        this.secret = config.getSecret();
        this.deviceType = config.getDeviceType();
        this.alias = config.getAlias();
        this.operatingSystem = config.getOperatingSystem();
        this.osVersion = config.getOsVersion();
        this.categories = new ArrayList<String>(config.getCategories());
        try {
            this.deviceRegistryURL = UrlUtils.appendToBaseURL(config.getPushServerURI().toURL(), registryDeviceEndpoint);
            this.metricsURL = UrlUtils.appendToBaseURL(config.getPushServerURI().toURL(), metricsEndpoint);
        } catch (MalformedURLException ex) {
            Log.e(TAG, ex.getMessage());
            throw new IllegalStateException("pushserverUrl was not a valid URL");
        }

        

    }

    @Override
    public void register(final Context context, final Callback<Void> callback) {

        if (FirebaseApp.getApps(context).size() == 0) {
            FirebaseOptions options = new FirebaseOptions.Builder().setGcmSenderId(senderId.split(":")[1]).setApplicationId(senderId).build();
            FirebaseApp.initializeApp(context, options);
        }
        
        new AsyncTask<Void, Void, Exception>() {

            @Override
            protected Exception doInBackground(Void... params) {

                try {

                    removeLegacyRegistrationId(context);

                    if (instanceId == null) {
                        instanceId = firebaseInstanceIdProvider.get(context);
                    }
                    String token = instanceId.getToken();

                    deviceToken = token;

                    HttpProvider httpProvider = httpProviderProvider.get(deviceRegistryURL, TIMEOUT);
                    setPasswordAuthentication(variantId, secret, httpProvider);

                    try {
                        JsonObject postData = new JsonObject();
                        postData.addProperty("deviceType", deviceType);
                        postData.addProperty("deviceToken", deviceToken);
                        postData.addProperty("alias", alias);
                        postData.addProperty("operatingSystem", operatingSystem);
                        postData.addProperty("osVersion", osVersion);
                        if (categories != null && !categories.isEmpty()) {
                            JsonArray jsonCategories = new JsonArray();
                            for (String category : categories) {
                                jsonCategories.add(new JsonPrimitive(category));
                            }
                            postData.add("categories", jsonCategories);
                        }

                        httpProvider.post(postData.toString());

                        postData.addProperty("deviceRegistryURL", deviceRegistryURL.toString());
                        postData.addProperty("variantId", variantId);
                        postData.addProperty("secret", secret);
                        presistPostInformation(context.getApplicationContext(), postData);
                        FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.get(context);

                        for (String catgory : categories) {
                            firebaseMessaging.subscribeToTopic(catgory);
                        }

                        //Subscribe to global topic
                        firebaseMessaging.subscribeToTopic(variantId);
                        return null;
                    } catch (HttpException ex) {
                        return ex;
                    }

                } catch (Exception ex) {
                    return ex;
                }

            }

            @SuppressWarnings("unchecked")
            @Override
            protected void onPostExecute(Exception result) {
                if (result == null) {
                    callback.onSuccess(null);
                } else if (result instanceof HttpException) {
                    HttpException httpException = (HttpException) result;
                    switch (httpException.getStatusCode()) {
                        case HttpURLConnection.HTTP_MOVED_PERM:
                        case HttpURLConnection.HTTP_MOVED_TEMP:
                        case 307://Temporary Redirect not in HTTPUrlConnection
                            Log.w(TAG, httpException.getMessage());
                            try {
                                URL redirectURL = new URL(httpException.getHeaders().get("Location"));
                                AeroGearFCMPushRegistrar.this.deviceRegistryURL = redirectURL;
                                register(context, callback);
                            } catch (MalformedURLException e) {
                                callback.onFailure(e);
                            }
                            break;
                        default:
                            callback.onFailure(result);
                    }
                } else {
                    callback.onFailure(result);
                }
            }

        }.execute((Void) null);

    }

    /**
     * Unregister device from Unified Push Server.
     *
     * if the device isn't registered onFailure will be called
     *
     * @param context Android application context
     * @param callback a callback.
     */
    @Override
    public void unregister(final Context context, final Callback<Void> callback) {
        new AsyncTask<Void, Void, Exception>() {
            @Override
            protected Exception doInBackground(Void... params) {

                try {

                    if ((deviceToken == null) || (deviceToken.trim().equals(""))) {
                        throw new IllegalStateException(DEVICE_ALREADY_UNREGISTERED);
                    }

                    if (instanceId == null) {
                        instanceId = firebaseInstanceIdProvider.get(context);
                    }
                    String token = instanceId.getToken();

                    FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.get(context);

                    for (String catgory : categories) {
                        firebaseMessaging.unsubscribeFromTopic(catgory);
                    }

                    //Unsubscribe to generic topic
                    firebaseMessaging.unsubscribeFromTopic(variantId);

                    instanceId.deleteInstanceId();

                    HttpProvider provider = httpProviderProvider.get(deviceRegistryURL, TIMEOUT);
                    setPasswordAuthentication(variantId, secret, provider);

                    try {
                        provider.delete(deviceToken);
                        deviceToken = "";
                        removeSavedPostData(context.getApplicationContext());
                        return null;
                    } catch (HttpException ex) {
                        return ex;
                    }

                } catch (Exception ex) {
                    return ex;
                }

            }

            @SuppressWarnings("unchecked")
            @Override
            protected void onPostExecute(Exception result) {
                if (result == null) {
                    callback.onSuccess(null);
                } else {
                    callback.onFailure(result);
                }
            }

        }.execute((Void) null);
    }

    /**
     * Send a confirmation the message was opened
     *
     * @param metricsMessage The id of the message received
     * @param callback a callback.
     */
    @Override
    public void sendMetrics(final UnifiedPushMetricsMessage metricsMessage,
            final Callback<UnifiedPushMetricsMessage> callback) {
        new AsyncTask<Void, Void, Exception>() {
            @Override
            protected Exception doInBackground(Void... params) {

                try {

                    if ((metricsMessage.getMessageId() == null) || (metricsMessage.getMessageId().trim().equals(""))) {
                        throw new IllegalStateException("Message ID cannot be null or blank");
                    }

                    HttpProvider provider = httpProviderProvider.get(metricsURL, TIMEOUT);
                    setPasswordAuthentication(variantId, secret, provider);

                    try {
                        provider.put(metricsMessage.getMessageId(), "");
                        return null;
                    } catch (HttpException ex) {
                        return ex;
                    }

                } catch (Exception ex) {
                    return ex;
                }

            }

            @SuppressWarnings("unchecked")
            @Override
            protected void onPostExecute(Exception result) {
                if (result == null) {
                    callback.onSuccess(metricsMessage);
                } else {
                    callback.onFailure(result);
                }
            }

        }.execute((Void) null);
    }

    public void setPasswordAuthentication(final String username, final String password, final HttpProvider provider) {
        provider.setDefaultHeader(BASIC_HEADER, getHashedAuth(username, password.toCharArray()));
    }

    private String getHashedAuth(String username, char[] password) {
        StringBuilder headerValueBuilder = new StringBuilder(AUTHORIZATION_METHOD).append(" ");
        String unhashedCredentials = new StringBuilder(username).append(":").append(password).toString();
        String hashedCrentials = Base64.encodeToString(unhashedCredentials.getBytes(), Base64.DEFAULT | Base64.NO_WRAP);
        return headerValueBuilder.append(hashedCrentials).toString();
    }

    /**
     * Save the post sent to UPS. This will be used by
     * {@link UnifiedPushInstanceIDListenerService} to refresh the registration
     * token if the registration token changes.
     *
     * @param appContext the application Context
     */
    private void presistPostInformation(Context appContext, JsonObject postData) {
        preferenceProvider.get(appContext).edit()
                .putString(String.format(REGISTRAR_PREFERENCE_TEMPLATE, senderId), postData.toString())
                .commit();
    }

    /**
     * We are no longer registered. We do not need to respond to changes in
     * registration token.
     *
     * @param appContext the application Context
     */
    private void removeSavedPostData(Context appContext) {
        preferenceProvider.get(appContext).edit()
                .remove(String.format(REGISTRAR_PREFERENCE_TEMPLATE, senderId))
                .commit();
    }

    /**
     * Gets the current registration id for application on FCM service.
     * <p>
     * If result is empty, the registration has failed.
     *
     * @param context the application context
     *
     * @return registration id, or empty string if the registration is not
     * complete.
     */
    private void removeLegacyRegistrationId(Context context) {
        try {
            final SharedPreferences prefs = context.getSharedPreferences(AeroGearFCMPushRegistrar.class.getSimpleName(), Context.MODE_PRIVATE);
            String registrationId = prefs.getString(LEGACY_PROPERTY_REG_ID, "");
            if (registrationId.length() != 0) {
                Log.v(TAG, "Found legacy ID: '" + registrationId + "'");

                HttpProvider provider = httpProviderProvider.get(deviceRegistryURL, TIMEOUT);
                setPasswordAuthentication(variantId, secret, provider);

                provider.delete(registrationId);
                prefs.edit().remove(LEGACY_PROPERTY_REG_ID).commit();

            }
        } catch (Exception ignore) {
            Log.v(TAG, "Exception Thrown attempting to unregister legacy token", ignore);
        }

    }

}
