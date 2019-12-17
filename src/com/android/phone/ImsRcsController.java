/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.telephony.ims.ImsException;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsRcsController;
import android.telephony.ims.aidl.IRcsUceControllerCallback;
import android.telephony.ims.feature.RcsFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import com.android.ims.RcsFeatureManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.imsphone.ImsPhone;

import java.util.List;

/**
 * Implementation of the IImsRcsController interface.
 */
public class ImsRcsController extends IImsRcsController.Stub {
    private static final String TAG = "ImsRcsController";

    /** The singleton instance. */
    private static ImsRcsController sInstance;

    private PhoneGlobals mApp;

    /**
     * Initialize the singleton ImsRcsController instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    static ImsRcsController init(PhoneGlobals app) {
        synchronized (ImsRcsController.class) {
            if (sInstance == null) {
                sInstance = new ImsRcsController(app);
            } else {
                Log.wtf(TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private ImsRcsController(PhoneGlobals app) {
        Log.i(TAG, "ImsRcsController");
        mApp = app;
        ServiceManager.addService(Context.TELEPHONY_IMS_SERVICE, this);
    }

    /**
     * Register a capability callback which will provide RCS availability updates for the
     * subscription specified.
     *
     * @param subId the subscription ID
     * @param callback The ImsCapabilityCallback to be registered.
     */
    @Override
    public void registerRcsAvailabilityCallback(int subId, IImsCapabilityCallback callback)
            throws RemoteException {
        enforceReadPrivilegedPermission("registerRcsAvailabilityCallback");
        final long token = Binder.clearCallingIdentity();
        try {
            getRcsFeatureManager(subId).registerRcsAvailabilityCallback(callback);
        } catch (com.android.ims.ImsException e) {
            Log.e(TAG, "registerRcsAvailabilityCallback: sudId=" + subId + ", " + e.getMessage());
            throw new ServiceSpecificException(e.getCode());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Remove the registered capability callback.
     *
     * @param subId the subscription ID
     * @param callback The ImsCapabilityCallback to be removed.
     */
    @Override
    public void unregisterRcsAvailabilityCallback(int subId, IImsCapabilityCallback callback) {
        enforceReadPrivilegedPermission("unregisterRcsAvailabilityCallback");
        final long token = Binder.clearCallingIdentity();
        try {
            getRcsFeatureManager(subId).unregisterRcsAvailabilityCallback(callback);
        } catch (com.android.ims.ImsException e) {
            Log.e(TAG, "unregisterRcsAvailabilityCallback: sudId=" + subId + "," + e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Query for the capability of an IMS RCS service
     *
     * @param subId the subscription ID
     * @param capability the RCS capability to query.
     * @param radioTech the radio tech that this capability failed for
     * @return true if the RCS capability is capable for this subscription, false otherwise.
     */
    @Override
    public boolean isCapable(int subId,
            @RcsFeature.RcsImsCapabilities.RcsImsCapabilityFlag int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech) {
        enforceReadPrivilegedPermission("isCapable");
        final long token = Binder.clearCallingIdentity();
        try {
            return getRcsFeatureManager(subId).isCapable(capability, radioTech);
        } catch (com.android.ims.ImsException e) {
            Log.e(TAG, "isCapable: sudId=" + subId
                    + ", capability=" + capability + ", " + e.getMessage());
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Query the availability of an IMS RCS capability.
     *
     * @param subId the subscription ID
     * @param capability the RCS capability to query.
     * @return true if the RCS capability is currently available for the associated subscription,
     * false otherwise.
     */
    @Override
    public boolean isAvailable(int subId,
            @RcsFeature.RcsImsCapabilities.RcsImsCapabilityFlag int capability) {
        enforceReadPrivilegedPermission("isAvailable");
        final long token = Binder.clearCallingIdentity();
        try {
            return getRcsFeatureManager(subId).isAvailable(capability);
        } catch (com.android.ims.ImsException e) {
            Log.e(TAG, "isAvailable: sudId=" + subId
                    + ", capability=" + capability + ", " + e.getMessage());
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void requestCapabilities(int subId, List<Uri> contactNumbers,
            IRcsUceControllerCallback c) {
        enforceReadPrivilegedPermission("requestCapabilities");
    }

    @Override
    public int getUcePublishState(int subId) {
        enforceReadPrivilegedPermission("getUcePublishState");
        return -1;
    }

    @Override
    public boolean isUceSettingEnabled(int subId) {
        enforceReadPrivilegedPermission("isUceSettingEnabled");
        return false;
    }

    @Override
    public void setUceSettingEnabled(int subId, boolean isEnabled) {
        enforceModifyPermission();
    }

    /**
     * Make sure either called from same process as self (phone) or IPC caller has read privilege.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceReadPrivilegedPermission(String message) {
        mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, message);
    }

    /**
     * Make sure the caller has the MODIFY_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceModifyPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }

    /**
     * Retrieve RcsFeatureManager instance.
     *
     * @param subId the subscription ID
     * @return The RcsFeatureManager instance
     * @throws SecurityException if getting Phone or RcsFeatureManager instance failed.
     */
    private RcsFeatureManager getRcsFeatureManager(int subId) {
        Phone phone = PhoneGlobals.getPhone(subId);
        if (phone == null) {
            throw new ServiceSpecificException(ImsException.CODE_ERROR_INVALID_SUBSCRIPTION,
                    "Invalid subscription Id: " + subId);
        }
        ImsPhone imsPhone = (ImsPhone) phone.getImsPhone();
        if (imsPhone == null) {
            throw new ServiceSpecificException(ImsException.CODE_ERROR_UNSUPPORTED_OPERATION,
                    "Cannot find ImsPhone instance: " + subId);
        }
        RcsFeatureManager rcsFeatureManager = imsPhone.getRcsManager();
        if (rcsFeatureManager == null) {
            throw new ServiceSpecificException(ImsException.CODE_ERROR_SERVICE_UNAVAILABLE,
                    "Cannot find RcsFeatureManager instance: " + subId);
        }
        return rcsFeatureManager;
    }
}
