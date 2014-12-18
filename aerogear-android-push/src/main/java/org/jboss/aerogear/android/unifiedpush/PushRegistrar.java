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
package org.jboss.aerogear.android.unifiedpush;

import android.content.Context;
import org.jboss.aerogear.android.Callback;

public interface PushRegistrar {

    /**
     * 
     * Registers a device to a push network and any 3rd party application servers.
     * 
     * @param context Android application context
     * @param callback If registrations is successful, the callback's
     * onSuccess method will be called and the device will begin receiving push 
     * messages.  If registration fails the on onFailure method will be called 
     * with a Exception as a parameter.  It is the application developer's 
     * responsibility to resolve the error and repeat registration.
     */
    void register(final Context context, final Callback<Void> callback);

    /**
     * 
     * Unregisters a device a push network and any party application servers.
     * 
     * @param context Android application context
     * @param callback The onSuccess method will be called after unregistration.  
     * The device will not receive further notifications.  If there is an error
     * onFailure will be called with an appropriate Exception.  It is the 
     * developer's responsibility to resolve the error and ensure the device is 
     * properly unregistered.
     */
    void unregister(final Context context, final Callback<Void> callback);

}
