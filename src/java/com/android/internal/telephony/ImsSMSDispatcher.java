/*
 * Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import static android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE;

import java.util.ArrayList;
import java.util.HashMap;

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.os.AsyncResult;
import android.os.Message;
import android.provider.Telephony.Sms.Intents;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;

import com.android.internal.R;
import com.android.internal.telephony.cdma.CdmaSMSDispatcher;
import com.android.internal.telephony.gsm.GsmSMSDispatcher;
import com.android.internal.telephony.InboundSmsHandler;
import com.android.internal.telephony.gsm.GsmInboundSmsHandler;
import com.android.internal.telephony.cdma.CdmaInboundSmsHandler;
import com.android.internal.telephony.SmsBroadcastUndelivered;

public class ImsSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "RIL_ImsSms";

    protected SMSDispatcher mCdmaDispatcher;
    protected SMSDispatcher mGsmDispatcher;
    protected GsmInboundSmsHandler mGsmInboundSmsHandler;
    protected CdmaInboundSmsHandler mCdmaInboundSmsHandler;


    /** true if IMS is registered and sms is supported, false otherwise.*/
    private boolean mIms = false;
    private String mImsSmsFormat = SmsConstants.FORMAT_UNKNOWN;

    /**
     * true if MO SMS over IMS is enabled. Default value is true. false for
     * carriers with config_send_sms1x_on_voice_call = true when attached to
     * eHRPD and during active 1x voice call
     */
    private boolean mImsSmsEnabled = true;

    public ImsSMSDispatcher(PhoneBase phone, SmsStorageMonitor storageMonitor,
            SmsUsageMonitor usageMonitor) {
        super(phone, usageMonitor, null);
        Rlog.d(TAG, "ImsSMSDispatcher created");

        initDispatchers(phone, storageMonitor, usageMonitor);

        mCi.registerForOn(this, EVENT_RADIO_ON, null);
        mCi.registerForImsNetworkStateChanged(this, EVENT_IMS_STATE_CHANGED, null);
    }

    protected void initDispatchers(PhoneBase phone, SmsStorageMonitor storageMonitor,
            SmsUsageMonitor usageMonitor) {
        // Create dispatchers, inbound SMS handlers and
        // broadcast undelivered messages in raw table.
        mCdmaDispatcher = new CdmaSMSDispatcher(phone, usageMonitor, this);
        mGsmInboundSmsHandler = GsmInboundSmsHandler.makeInboundSmsHandler(phone.getContext(),
                storageMonitor, phone);
        mCdmaInboundSmsHandler = CdmaInboundSmsHandler.makeInboundSmsHandler(phone.getContext(),
                storageMonitor, phone, (CdmaSMSDispatcher) mCdmaDispatcher);
        mGsmDispatcher = new GsmSMSDispatcher(phone, usageMonitor, this, mGsmInboundSmsHandler);
        Thread broadcastThread = new Thread(new SmsBroadcastUndelivered(phone.getContext(),
                mGsmInboundSmsHandler, mCdmaInboundSmsHandler));
        broadcastThread.start();
    }


    /* Updates the phone object when there is a change */
    @Override
    protected void updatePhoneObject(PhoneBase phone) {
        Rlog.d(TAG, "In IMS updatePhoneObject ");
        super.updatePhoneObject(phone);
        mCdmaDispatcher.updatePhoneObject(phone);
        mGsmDispatcher.updatePhoneObject(phone);
        mGsmInboundSmsHandler.updatePhoneObject(phone);
        mCdmaInboundSmsHandler.updatePhoneObject(phone);
    }

    public void dispose() {
        mCi.unregisterForOn(this);
        mCi.unregisterForImsNetworkStateChanged(this);
        mGsmDispatcher.dispose();
        mCdmaDispatcher.dispose();
        mGsmInboundSmsHandler.dispose();
        mCdmaInboundSmsHandler.dispose();
    }

    /**
     * Handles events coming from the phone stack. Overridden from handler.
     *
     * @param msg the message to handle
     */
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        switch (msg.what) {
        case EVENT_RADIO_ON:
        case EVENT_IMS_STATE_CHANGED: // received unsol
            mCi.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
            break;

        case EVENT_IMS_STATE_DONE:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                updateImsInfo(ar);
            } else {
                Rlog.e(TAG, "IMS State query failed with exp "
                        + ar.exception);
            }
            break;

        default:
            super.handleMessage(msg);
        }
    }

    private void setImsSmsFormat(int format) {
        // valid format?
        switch (format) {
            case PhoneConstants.PHONE_TYPE_GSM:
                mImsSmsFormat = "3gpp";
                break;
            case PhoneConstants.PHONE_TYPE_CDMA:
                mImsSmsFormat = "3gpp2";
                break;
            default:
                mImsSmsFormat = "unknown";
                break;
        }
    }

    private void updateImsInfo(AsyncResult ar) {
        int[] responseArray = (int[])ar.result;

        mIms = false;
        if (responseArray[0] == 1) {  // IMS is registered
            Rlog.d(TAG, "IMS is registered!");
            mIms = true;
        } else {
            Rlog.d(TAG, "IMS is NOT registered!");
        }

        setImsSmsFormat(responseArray[1]);

        if (("unknown".equals(mImsSmsFormat))) {
            Rlog.e(TAG, "IMS format was unknown!");
            // failed to retrieve valid IMS SMS format info, set IMS to unregistered
            mIms = false;
        }
    }

    @Override
    protected void sendData(String destAddr, String scAddr, int destPort, int origPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        if (isCdmaMo()) {
            mCdmaDispatcher.sendData(destAddr, scAddr, destPort, origPort,
                    data, sentIntent, deliveryIntent);
        } else {
            mGsmDispatcher.sendData(destAddr, scAddr, destPort, origPort,
                    data, sentIntent, deliveryIntent);
        }
    }

    @Override
    protected void sendMultipartText(String destAddr, String scAddr,
            ArrayList<String> parts, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents, int priority) {
        if (isCdmaMo()) {
            mCdmaDispatcher.sendMultipartText(destAddr, scAddr,
                    parts, sentIntents, deliveryIntents, priority);
        } else {
            mGsmDispatcher.sendMultipartText(destAddr, scAddr,
                    parts, sentIntents, deliveryIntents, priority);
        }
    }

    @Override
    protected void sendSms(SmsTracker tracker) {
        //  sendSms is a helper function to other send functions, sendText/Data...
        //  it is not part of ISms.stub
        Rlog.e(TAG, "sendSms should never be called from here!");
    }

    @Override
    protected void sendText(String destAddr, String scAddr, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent, int priority) {
        Rlog.d(TAG, "sendText");
        if (isCdmaMo()) {
            mCdmaDispatcher.sendText(destAddr, scAddr,
                    text, sentIntent, deliveryIntent, priority);
        } else {
            mGsmDispatcher.sendText(destAddr, scAddr,
                    text, sentIntent, deliveryIntent, priority);
        }
    }

    @Override
    public void sendRetrySms(SmsTracker tracker) {
        String oldFormat = tracker.mFormat;

        // newFormat will be based on voice technology
        String newFormat =
            (PhoneConstants.PHONE_TYPE_CDMA == mPhone.getPhoneType()) ?
                    mCdmaDispatcher.getFormat() :
                        mGsmDispatcher.getFormat();

        // was previously sent sms format match with voice tech?
        if (oldFormat.equals(newFormat)) {
            if (isCdmaFormat(newFormat)) {
                Rlog.d(TAG, "old format matched new format (cdma)");
                mCdmaDispatcher.sendSms(tracker);
                return;
            } else {
                Rlog.d(TAG, "old format matched new format (gsm)");
                mGsmDispatcher.sendSms(tracker);
                return;
            }
        }

        // format didn't match, need to re-encode.
        HashMap map = tracker.mData;

        // to re-encode, fields needed are:  scAddr, destAddr, and
        //   text if originally sent as sendText or
        //   data and destPort if originally sent as sendData.
        if (!( map.containsKey("scAddr") && map.containsKey("destAddr") &&
               ( map.containsKey("text") ||
                       (map.containsKey("data") && map.containsKey("destPort"))))) {
            // should never come here...
            Rlog.e(TAG, "sendRetrySms failed to re-encode per missing fields!");
            if (tracker.mSentIntent != null) {
                int error = RESULT_ERROR_GENERIC_FAILURE;
                // Done retrying; return an error to the app.
                try {
                    tracker.mSentIntent.send(mContext, error, null);
                } catch (CanceledException ex) {}
            }
            return;
        }
        String scAddr = (String)map.get("scAddr");
        String destAddr = (String)map.get("destAddr");

        SmsMessageBase.SubmitPduBase pdu = null;
        //    figure out from tracker if this was sendText/Data
        if (map.containsKey("text")) {
            Rlog.d(TAG, "sms failed was text");
            String text = (String)map.get("text");

            if (isCdmaFormat(newFormat)) {
                Rlog.d(TAG, "old format (gsm) ==> new format (cdma)");
                pdu = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(
                        scAddr, destAddr, text, (tracker.mDeliveryIntent != null), null);
            } else {
                Rlog.d(TAG, "old format (cdma) ==> new format (gsm)");
                pdu = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(
                        scAddr, destAddr, text, (tracker.mDeliveryIntent != null), null);
            }
        } else if (map.containsKey("data")) {
            Rlog.d(TAG, "sms failed was data");
            byte[] data = (byte[])map.get("data");
            Integer destPort = (Integer)map.get("destPort");

            if (isCdmaFormat(newFormat)) {
                Rlog.d(TAG, "old format (gsm) ==> new format (cdma)");
                pdu = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(
                            scAddr, destAddr, destPort.intValue(), data,
                            (tracker.mDeliveryIntent != null));
            } else {
                Rlog.d(TAG, "old format (cdma) ==> new format (gsm)");
                pdu = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(
                            scAddr, destAddr, destPort.intValue(), data,
                            (tracker.mDeliveryIntent != null));
            }
        }

        // replace old smsc and pdu with newly encoded ones
        map.put("smsc", pdu.encodedScAddress);
        map.put("pdu", pdu.encodedMessage);

        SMSDispatcher dispatcher = (isCdmaFormat(newFormat)) ?
                mCdmaDispatcher : mGsmDispatcher;

        tracker.mFormat = dispatcher.getFormat();
        dispatcher.sendSms(tracker);
    }

    @Override
    protected String getFormat() {
        // this function should be defined in Gsm/CdmaDispatcher.
        Rlog.e(TAG, "getFormat should never be called from here!");
        return "unknown";
    }

    @Override
    protected GsmAlphabet.TextEncodingDetails calculateLength(
            CharSequence messageBody, boolean use7bitOnly) {
        Rlog.e(TAG, "Error! Not implemented for IMS.");
        return null;
    }

    @Override
    protected void sendNewSubmitPdu(String destinationAddress, String scAddress, String message,
            SmsHeader smsHeader, int format, PendingIntent sentIntent,
            PendingIntent deliveryIntent, boolean lastPart, int priority) {
        Rlog.e(TAG, "Error! Not implemented for IMS.");
    }

    @Override
    public boolean isIms() {
        return mIms;
    }

    @Override
    public String getImsSmsFormat() {
        return mImsSmsFormat;
    }

    /**
     * Determines whether or not to use CDMA format for MO SMS.
     * If SMS over IMS is supported, then format is based on IMS SMS format,
     * otherwise format is based on current phone type.
     *
     * @return true if Cdma format should be used for MO SMS, false otherwise.
     */
    private boolean isCdmaMo() {
        if (!isIms() || !shouldSendSmsOverIms()) {
            // Either IMS is not registered or there is an active 1x voice call
            // while on eHRPD, use Voice technology to determine SMS format.
            return (PhoneConstants.PHONE_TYPE_CDMA == mPhone.getPhoneType());
        }
        // IMS is registered with SMS support
        return isCdmaFormat(mImsSmsFormat);
    }

    /**
     * Determines whether or not format given is CDMA format.
     *
     * @param format
     * @return true if format given is CDMA format, false otherwise.
     */
    private boolean isCdmaFormat(String format) {
        return (mCdmaDispatcher.getFormat().equals(format));
    }

    /**
     * Enables MO SMS over IMS
     *
     * @param enable
     */
    public void enableSendSmsOverIms(boolean enable) {
        mImsSmsEnabled = enable;
    }

    /**
     * Determines whether MO SMS over IMS is currently enabled.
     *
     * @return true if MO SMS over IMS is enabled, false otherwise.
     */
    public boolean isImsSmsEnabled() {
        return mImsSmsEnabled;
    }

    /**
     * Determines whether SMS should be sent over IMS if UE is attached to eHRPD
     * and there is an active voice call
     *
     * @return true if SMS should be sent over IMS based on value in config.xml
     *         or system property false otherwise
     */
    public boolean shouldSendSmsOverIms() {
        boolean sendSmsOn1x = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_send_sms1x_on_voice_call);
        int currentCallState = mTelephonyManager.getCallState();
        int currentVoiceNetwork = mTelephonyManager.getVoiceNetworkType();
        int currentDataNetwork = mTelephonyManager.getDataNetworkType();

        Rlog.d(TAG, "data = " + currentDataNetwork + " voice = " + currentVoiceNetwork
                + " call state = " + currentCallState);

        if (sendSmsOn1x) {
            // The UE shall use 1xRTT for SMS if the UE is attached to an eHRPD
            // network and there is an active 1xRTT voice call.
            if (currentDataNetwork == TelephonyManager.NETWORK_TYPE_EHRPD
                    && currentVoiceNetwork == TelephonyManager.NETWORK_TYPE_1xRTT
                    && currentCallState != mTelephonyManager.CALL_STATE_IDLE) {
                enableSendSmsOverIms(false);
                return false;
            }
        }
        return true;
    }
}
