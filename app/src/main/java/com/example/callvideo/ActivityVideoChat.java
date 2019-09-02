package com.example.callvideo;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Camera;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.callvideo.util.Constants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;

import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import fi.vtt.nubomedia.kurentoroomclientandroid.KurentoRoomAPI;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomError;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomListener;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomNotification;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomResponse;
import fi.vtt.nubomedia.utilitiesandroid.LooperExecutor;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMMediaConfiguration;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMPeerConnection;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMWebRTCPeer;
import io.socket.client.IO;
import io.socket.client.Manager;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import io.socket.engineio.client.Transport;
import okhttp3.OkHttpClient;

public class ActivityVideoChat extends Activity implements NBMWebRTCPeer.Observer, RoomListener {

//    private static final String TAG = "ActivityVideoChat";
private static final String TAG = "SessionDescription";
    private static final String SDP = "SessionDescription";
    private static final String ICEICE = "icecandidate";
    private static final String RECONNECT = "reconnecting";
    private static final String CHECKCONNECTION = "myspeedconnection";
    private Socket socketIO;
    private static final String CONNECTSERVER = "connectServer";
    private static final String TESTCALL = "TESTCALL";
    private KurentoRoomAPI kurentoRoomAPI;
    private Thread  backPressedThread = null;
    private LooperExecutor looperExecutor;

    private NBMWebRTCPeer nbmWebRTCPeer;
    private NBMMediaConfiguration peerConnectionParameters;
    private SurfaceViewRenderer masterView;
    private SurfaceViewRenderer localView;

    private Map<Integer, String> videoRequestUserMapping;
    private int publishVideoRequestId;
    private TextView mCallStatus;
    private String  username;
    private boolean backPressed = false;
    private String customerId = "customer";

    private Handler mHandler = null;
    private CallState callState;
    private String mSessionDescription = "";
    private String token;
    private EglBase rootEglBase;
    private Button stopVideo;

    private long timeUnformatted = 0L;
    private long timeInMilliseconds = 0L;
    private long timeSwapBuff = 0L;
    private long updatedTime = 0L;
    private int SetLevel= 0;
    private int bWidth;
    private int bHeight;
    private int bandwidth;

    private enum CallState{
        IDLE, PUBLISHING, PUBLISHED, WAITING_REMOTE_USER, RECEIVING_REMOTE_USER
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectServer();
        setContentView(R.layout.activity_video_chat);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mHandler = new Handler();
        masterView = findViewById(R.id.gl_surface);
        localView = findViewById(R.id.gl_surface_local);
        stopVideo = findViewById(R.id.stopVideo);
        this.mCallStatus = findViewById(R.id.call_status);
        callState = CallState.IDLE;
        MainActivity.getKurentoRoomAPIInstance().addObserver(this);

        looperExecutor = new LooperExecutor();
        looperExecutor.requestStart();

        stopVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Toast.makeText(ActivityVideoChat.this, "Stopper", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(ActivityVideoChat.this, MainActivity.class);
                startActivity(intent);
                endCall();
                socketIO.disconnect();
            }
        });

        kurentoRoomAPI = new KurentoRoomAPI(looperExecutor, Constants.SOCKET_ADDRESS_HTTPS, ActivityVideoChat.this);
    }

    @Override
    protected void onStart() {
        super.onStart();

        Bundle extras = getIntent().getExtras();
        this.username = extras.getString(Constants.USER_NAME, "");
        bandwidth = extras.getInt("bandwidth");

        rootEglBase = EglBase.create();
        masterView.init(rootEglBase.getEglBaseContext(), null);
        masterView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);




        localView.init(rootEglBase.getEglBaseContext(), null);
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        localView.setMirror(true);

         peerConnectionParameters = new NBMMediaConfiguration(
                NBMMediaConfiguration.NBMRendererType.OPENGLES,
                NBMMediaConfiguration.NBMAudioCodec.OPUS, 0,
                NBMMediaConfiguration.NBMVideoCodec.VP8, checkConnection(),
                new NBMMediaConfiguration.NBMVideoFormat(1080, 2246, PixelFormat.RGB_888, 4),
                NBMMediaConfiguration.NBMCameraPosition.FRONT);
//        setNbmWebRTCPeer();

        videoRequestUserMapping = new HashMap<>();

        nbmWebRTCPeer = new NBMWebRTCPeer(peerConnectionParameters, this, localView, this);
        nbmWebRTCPeer.registerMasterRenderer(masterView);

        nbmWebRTCPeer.initialize();

        callState = CallState.PUBLISHING;

    }

    @Override
    protected void onStop() {
        Log.d(TESTCALL,"1");
        endCall();
        super.onStop();
    }

    @Override
    protected void onPause() {
        Log.d(TESTCALL,"2");
        if (nbmWebRTCPeer !=null){
            nbmWebRTCPeer.stopLocalMedia();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.d(TESTCALL,"3");
        super.onResume();
        nbmWebRTCPeer.startLocalMedia();
    }

    @Override
    protected void onDestroy() {
        Log.d(TESTCALL,"4");
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.d(TESTCALL,"5");
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_video_chat, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(TESTCALL,"6");
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onBackPressed() {
        Log.d(TESTCALL,"7");
        // Data channel test code
        /*DataChannel channel = nbmWebRTCPeer.getDataChannel("local", "test_channel_static");
        if (channel.state() == DataChannel.State.OPEN) {
            sendHelloMessage(channel);
            Log.i(TAG, "[datachannel] Datachannel open, sending hello");
        }
        else {
            Log.i(TAG, "[datachannel] Channel is not open! State: " + channel.state());
        }
        Log.i(TAG, "[DataChannel] Testing for existing channel");
        DataChannel channel =  nbmWebRTCPeer.getDataChannel("local", "default");
        if (channel == null) {
            DataChannel.Init init = new DataChannel.Init();
            init.negotiated = false;
            init.ordered = true;
            Log.i(TAG, "[DataChannel] Channel does not exist, creating...");
            channel = nbmWebRTCPeer.createDataChannel("local", "test_channel", init);
        }
        else {
            Log.i(TAG, "[DataChannel] Channel already exists. State: " + channel.state());
            sendHelloMessage(channel);
        }*/

        // If back button has not been pressed in a while then trigger thread and toast notification
        if (!this.backPressed){
            this.backPressed = true;
            Toast.makeText(this,"Press back again to end.", Toast.LENGTH_SHORT).show();
            this.backPressedThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1000);
                        backPressed = false;
                    } catch (InterruptedException e){ Log.d("VCA-oBP","Successfully interrupted"); }
                }
            });
            this.backPressedThread.start();
        }
        // If button pressed the second time then call super back pressed
        // (eventually calls onDestroy)
        else {
            if (this.backPressedThread != null)
                this.backPressedThread.interrupt();
            super.onBackPressed();
        }
    }

    public void hangup(View view) {
        finish();
    }

    private void GenerateOfferForRemote(String remote_name){
        nbmWebRTCPeer.generateOffer(remote_name, false);
        callState = CallState.WAITING_REMOTE_USER;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCallStatus.setText(R.string.waiting_remote_stream);
            }
        });
    }

    public void receiveFromRemote(View view){
        //GenerateOfferForRemote();
    }

    /**
     * Terminates the current call and ends activity
     */
    private void endCall() {
        callState = CallState.IDLE;
        try
        {
            if (nbmWebRTCPeer != null) {
                nbmWebRTCPeer.close();
                nbmWebRTCPeer = null;
            }
        }
        catch (Exception e){e.printStackTrace();}
    }

    @Override
    public void onInitialize() {
        Log.d(TESTCALL,"112");
        nbmWebRTCPeer.generateOffer("local", true);
    }

    @Override
    public void onLocalSdpOfferGenerated(final SessionDescription sessionDescription, final NBMPeerConnection nbmPeerConnection) {
        publishVideoRequestId = ++Constants.id;
        Log.d(TESTCALL,"11");
        Log.d(RECONNECT, "reconnecting " + "onLocalSdpOfferGenerated");
        if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED) {
            Log.d(SDP, "onLocalSdpOfferGenerated in if" + sessionDescription.description);
            mSessionDescription = sessionDescription.description;
            kurentoRoomAPI.sendPublishVideo(sessionDescription.description, false, publishVideoRequestId);
            sendSocketParameter(mSessionDescription);
            MainActivity.getKurentoRoomAPIInstance().sendPublishVideo(sessionDescription.description, false, publishVideoRequestId);

        } else { // Asking for remote user video
            Log.d(SDP, "onLocalSdpOfferGenerated in else" + sessionDescription.description);
            mSessionDescription = sessionDescription.description;
            sendSocketParameter(mSessionDescription);
            publishVideoRequestId = ++Constants.id;
            String username = nbmPeerConnection.getConnectionId();
            videoRequestUserMapping.put(publishVideoRequestId, username);
            MainActivity.getKurentoRoomAPIInstance().sendReceiveVideoFrom(username, "webcam", sessionDescription.description, publishVideoRequestId);
        }
    }

    @Override
    public void onLocalSdpAnswerGenerated(SessionDescription sessionDescription, NBMPeerConnection nbmPeerConnection) {
        Log.d(TESTCALL,"111");
        Log.d(SDP, "onLocalSdpAnswerGenerated" + sessionDescription.description);
        Log.d(RECONNECT, "reconnecting " + "onLocalSdpAnswerGenerated");
    }

    @Override
    public void onIceCandidate(IceCandidate iceCandidate, NBMPeerConnection nbmPeerConnection) {
        Log.d(TESTCALL,"09");
        Log.d(RECONNECT, "onIceCandidate func" + iceCandidate);
        int sendIceCandidateRequestId = ++Constants.id;
        sendOnIceCandidate(iceCandidate.sdp);

        if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED){
            kurentoRoomAPI.sendOnIceCandidate(this.username, iceCandidate.sdp,
                    iceCandidate.sdpMid, Integer.toString(iceCandidate.sdpMLineIndex), 12);
        } else {
            kurentoRoomAPI.sendOnIceCandidate(nbmPeerConnection.getConnectionId(), iceCandidate.sdp,
                    iceCandidate.sdpMid, Integer.toString(iceCandidate.sdpMLineIndex), 12);

        }
    }

    @Override
    public void onIceStatusChanged(PeerConnection.IceConnectionState iceConnectionState, NBMPeerConnection nbmPeerConnection) {
        Log.d(TESTCALL,"14");
        Log.i("iceconnectionstate", "iceConnectionState " + iceConnectionState);
        Log.i(RECONNECT, "iceConnectionState " + nbmPeerConnection);

        switch (iceConnectionState){
            case CONNECTED:
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mCallStatus.setVisibility(View.VISIBLE);
                        mCallStatus.setText("");
                    }
                });
                break;
            case DISCONNECTED:
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mCallStatus.setVisibility(View.VISIBLE);
                        mCallStatus.setText("Reconnecting");
                    }
                });
                break;
        }
    }

    @Override
    public void onRemoteStreamAdded(MediaStream mediaStream, NBMPeerConnection nbmPeerConnection) {
        Log.d(TESTCALL,"16");
        Log.i(RECONNECT, "onRemoteStreamAdded");
        nbmWebRTCPeer.setActiveMasterStream(mediaStream);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCallStatus.setText("");
            }
        });
    }

    @Override
    public void onRemoteStreamRemoved(MediaStream mediaStream, NBMPeerConnection nbmPeerConnection) {
        Log.d(TESTCALL,"546");
        Log.i(CONNECTSERVER, "onRemoteStreamRemoved");
    }

    @Override
    public void onPeerConnectionError(String s) {
        Log.d(TESTCALL,"DSF");

        Log.e(RECONNECT, "onPeerConnectionError:" + s);
    }

    @Override
    public void onDataChannel(DataChannel dataChannel, NBMPeerConnection connection) {
        Log.d(TESTCALL,"VDXC");
        Log.i(TAG, "[datachannel] Peer opened data channel");
        Log.i(RECONNECT, "onRemoteStreamAdded");

    }

    @Override
    public void onBufferedAmountChange(long l, NBMPeerConnection connection, DataChannel channel) {
        Log.d(TESTCALL,"1sasd1");
        Log.d(RECONNECT,".,CXZ.,");
    }

    public void sendHelloMessage(DataChannel channel) {
        byte[] rawMessage = "Hello Peer!".getBytes(Charset.forName("UTF-8"));
        ByteBuffer directData = ByteBuffer.allocateDirect(rawMessage.length);
        directData.put(rawMessage);
        directData.flip();
        DataChannel.Buffer data = new DataChannel.Buffer(directData, false);
        channel.send(data);
    }

    @Override
    public void onStateChange(NBMPeerConnection connection, DataChannel channel) {
        Log.d(TESTCALL,"11cxc");
        Log.i(TAG, "[datachannel] DataChannel onStateChange: " + channel.state());
        Log.i(RECONNECT, "onRemoteStreamAdded");
        if (channel.state() == DataChannel.State.OPEN) {
            sendHelloMessage(channel);
            Log.i(TAG, "[datachannel] Datachannel open, sending first hello");
        }
    }

    @Override
    public void onMessage(DataChannel.Buffer buffer, NBMPeerConnection connection, DataChannel channel) {
        Log.d(TESTCALL,";'L;");
        Log.i(TAG, "[datachannel] Message received: " + buffer.toString());
        sendHelloMessage(channel);
    }

    private Runnable offerWhenReady = new Runnable() {
        @Override
        public void run() {
            Log.d(TESTCALL,"11");
            // Generate offers to receive video from all peers in the room
            for (Map.Entry<String, Boolean> entry : MainActivity.userPublishList.entrySet()) {
                if (entry.getValue()) {
                    GenerateOfferForRemote(entry.getKey());
                    Log.i(RECONNECT, "I'm " + username + " DERP: Generating offer for peer " + entry.getKey());
                    // Set value to false so that if this function is called again we won't
                    // generate another offer for this user
                    entry.setValue(false);
                }
            }
        }
    };

    @Override
    public void onRoomResponse(RoomResponse response) {
        Log.d(TESTCALL,"11aqwe");
        int requestId =response.getId();

        if (requestId == publishVideoRequestId){

            SessionDescription sd = new SessionDescription(SessionDescription.Type.ANSWER,
                    response.getValue("sdpAnswer").get(0));

            // Check if we are waiting for publication of our own vide
            if (callState == CallState.PUBLISHING){
                callState = CallState.PUBLISHED;
                nbmWebRTCPeer.processAnswer(sd, "local");
                mHandler.postDelayed(offerWhenReady, 2000);

                // Check if we are waiting for the video publication of the other peer
            } else if (callState == CallState.WAITING_REMOTE_USER){
                //String user_name = Integer.toString(publishVideoRequestId);
                callState = CallState.RECEIVING_REMOTE_USER;
                String connectionId = videoRequestUserMapping.get(publishVideoRequestId);
                nbmWebRTCPeer.processAnswer(sd, connectionId);
            }
        }

    }

    @Override
    public void onRoomError(RoomError error) {
        Log.d(TESTCALL,"11bbbbb");
        Log.d(TESTCALL,"DASDASD");

        Log.e(RECONNECT, "OnRoomError:" + error);
    }

    @Override
    public void onRoomNotification(RoomNotification notification) {
        Log.d(TESTCALL,"11;';'");
        Map<String, Object> map = notification.getParams();

        if(notification.getMethod().equals(RoomListener.METHOD_ICE_CANDIDATE)) {
            String sdpMid = map.get("sdpMid").toString();
            int sdpMLineIndex = Integer.valueOf(map.get("sdpMLineIndex").toString());
            String sdp = map.get("candidate").toString();
            IceCandidate ic = new IceCandidate(sdpMid, sdpMLineIndex, sdp);

            if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED) {
                nbmWebRTCPeer.addRemoteIceCandidate(ic, "local");
            } else {
                nbmWebRTCPeer.addRemoteIceCandidate(ic, notification.getParam("endpointName").toString());
            }
        }

        // Somebody in the room published their video
        else if(notification.getMethod().equals(RoomListener.METHOD_PARTICIPANT_PUBLISHED)) {
            mHandler.postDelayed(offerWhenReady, 2000);
        }
    }

    @Override
    public void onRoomConnected() {
        Log.i(RECONNECT, "onRemoteStreamAdded");
        Log.d(TESTCALL,"'SDAL';LWA'");
    }

    @Override
    public void onRoomDisconnected() {
        Log.i(RECONNECT, "onRemoteStreamAdded");
        Log.d(TESTCALL,"123123");
    }

    public void sendSocketParameter(String sessionDescription){
        String id = "call";
        String channel = "ONEMOBILE";

        try {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("customerId", customerId);
            obj.put("channel", channel);
            obj.put("token", Constants.PUBLIC_TOKEN);
            obj.put("sdpOffer", sessionDescription);
            obj.put("maxBandwidth", checkConnection());

            Log.d("cekcekcekcek", " video " + checkConnection());

            socketIO.emit("message", obj.toString());

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendOnIceCandidate(String onIceCandidate){
        Log.d(TESTCALL,"1qwee");
        JsonObject iceObject = new JsonObject();
        iceObject.addProperty("id", "onIceCandidateCustomer");
        iceObject.addProperty("candidate", onIceCandidate);

        socketIO.emit("message", iceObject.toString());

    }

    public void connectServer(){
        SSLContext mSslContext = null;

        HostnameVerifier hostnameVerifier = new HostnameVerifier() {
            @Override
            public boolean verify(String s, SSLSession sslSession) {
                HostnameVerifier hostnameVerifier1 = HttpsURLConnection.getDefaultHostnameVerifier();
                return hostnameVerifier1.verify(s, sslSession);
            }
        };

        TrustManager[] trustAllCertificates = new TrustManager[]{ new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {

                Log.d(CONNECTSERVER, "checkClientTrusted " + x509Certificates.toString() + s);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] x509Certificates, String s){
                Log.d(CONNECTSERVER, "checkServerTrusted " + x509Certificates.toString() + " , " + s);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};

        X509TrustManager trustManager = (X509TrustManager) trustAllCertificates[0];


        try {
            mSslContext = SSLContext.getInstance("TLS");
            try {
                mSslContext.init(null, trustAllCertificates, null);
            } catch (KeyManagementException e) {
                e.printStackTrace();
            }
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .hostnameVerifier(hostnameVerifier)
                .retryOnConnectionFailure(true)
                .sslSocketFactory(mSslContext.getSocketFactory(), trustManager)
                .build();

        IO.setDefaultOkHttpWebSocketFactory(okHttpClient);
        IO.setDefaultOkHttpCallFactory(okHttpClient);

        IO.Options options = new IO.Options();
        options.callFactory = okHttpClient;
        options.reconnection = true;
//        options.transports = new String[]{WebSocket.NAME};
        options.secure = true;
        options.forceNew = true;
        options.path = "/native";

        try {
            socketIO = IO.socket("http://" + Constants.SOCKET_ADDRESS_HTTPS, options);
            socketIO.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mCallStatus.setVisibility(View.VISIBLE);
                            mCallStatus.setText("");
                        }
                    });
                    Log.d(CONNECTSERVER, "EVENT_CONNECT " + args.toString());
                    Log.d(RECONNECT, "EVENT_CONNECT " + args.toString());


                }
            });
            socketIO.connect();

        } catch (URISyntaxException e) {
            Log.d(CONNECTSERVER, "exception " + e.toString());
        }

        socketIO.on(Socket.EVENT_MESSAGE, new Emitter.Listener() {
            @Override
            public void call(final Object... args) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String data = (String) args[0];
                        JsonObject jsonObject = new JsonParser().parse(data).getAsJsonObject();
                        String theId = jsonObject.get("id").getAsString();
                        Log.d(CONNECTSERVER, theId);
                        Log.d(RECONNECT, "EVENT_MESSAGE");

                        switch (theId){
                            case "registerResponse":
                                Log.d(CONNECTSERVER, "1");
                                break;
                            case "waitingQueue":
                                Log.d(CONNECTSERVER, "2");
                                mCallStatus.setVisibility(View.VISIBLE);
                                mCallStatus.setText("Connecting");
                                mCallStatus.setTextColor(Color.WHITE);
                                break;
                            case "iceCandidateCustomer":
                                Log.d(CONNECTSERVER, "3");
                                JsonObject getCandidate = jsonObject.get("candidate").getAsJsonObject();
                                String getSdp = getCandidate.get("candidate").getAsString();
                                String getSdpMid = getCandidate.get("sdpMid").getAsString();
                                int getSdpMidLineIndex = getCandidate.get("sdpMLineIndex").getAsInt();

                                IceCandidate iceCandidate = new IceCandidate(getSdpMid, getSdpMidLineIndex, getSdp);
                                Log.d(RECONNECT, iceCandidate.toString());
                                nbmWebRTCPeer.addRemoteIceCandidate(iceCandidate, "local");
                                break;
                            case "callResponse":
                                Log.d(CONNECTSERVER, "4");
                                mCallStatus.setVisibility(View.VISIBLE);
                                mCallStatus.setText("Yes");
                                mCallStatus.setTextColor(Color.WHITE);
                                String getResponse = jsonObject.get("response").getAsString();
                                String sdpAnswer = jsonObject.get("sdpAnswer").getAsString();

                                SessionDescription sd = new SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer );
                                nbmWebRTCPeer.processAnswer(sd, "local");
                                PeerConnectionFactory.initializeAndroidGlobals(ActivityVideoChat.this, true,true,true);
                                PeerConnectionFactory peerConnectionFactory = new PeerConnectionFactory();
                                peerConnectionFactory.setVideoHwAccelerationOptions(rootEglBase.getEglBaseContext(), rootEglBase.getEglBaseContext());
                                break;
                            case "stopByAgent":
                                Log.d(CONNECTSERVER, "5");
                                Toast.makeText(ActivityVideoChat.this, "Stopper", Toast.LENGTH_SHORT).show();
                                Intent intent = new Intent(ActivityVideoChat.this, MainActivity.class);
                                startActivity(intent);
                                default:
                                    Log.d("getMeIce", theId);
                                    break;
                        }
                    }
                });

            }
        });

        socketIO.on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.e(RECONNECT, "EVENT_CONNECT_ERROR " + args[0]);
            }
        });

        socketIO.on(Manager.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d(RECONNECT, "EVENT_OPEN");
            }
        });

        socketIO.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mCallStatus.setVisibility(View.VISIBLE);
                        mCallStatus.setText("Reconnecting");
                    }
                });
                Log.d(RECONNECT, "EVENT_DISCONNECT");
                try {
                    JSONObject reconnectObj = new JSONObject();
                    reconnectObj.put("id", "re-register");
                    reconnectObj.put("customerId", customerId);

                    Log.d(RECONNECT, reconnectObj.toString());
                    socketIO.emit("message", reconnectObj.toString());

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });

        socketIO.io().on(Manager.EVENT_TRANSPORT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Transport transport = (Transport) args[0];
                transport.on(Transport.EVENT_ERROR, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        Exception e = (Exception) args[0];
                        Log.e(CONNECTSERVER, "Transport " + e.getCause());
                        e.printStackTrace();
                        e.getCause().printStackTrace();
                    }
                });
            }
        });

        socketIO.on("reconnect", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mCallStatus.setText("Reconnecting");
                        mCallStatus.setVisibility(View.VISIBLE);

//                        mCallStatus.postDelayed(new Runnable() {
//                            @Override
//                            public void run() {
//                                mCallStatus.setVisibility(View.GONE);
//                            }
//                        },3000);
                    }
                });

                Log.d(RECONNECT, "called");
                try {
                    JSONObject reconnectObj = new JSONObject();
                    reconnectObj.put("id", "re-register");
                    reconnectObj.put("customerId", customerId);

                    Log.d(RECONNECT, reconnectObj.toString());
//                    socketIO.emit("message", reconnectObj.toString());

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });

        socketIO.on("callResponse", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d(RECONNECT, "CALL_RESPONSE");
            }
        });

    }

    private int setTimedHandler(){
        SetLevel = 0;
        final Handler timedHandler = new Handler();
        Timer timer = new Timer();
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                timedHandler.post(new Runnable() {
                    @Override
                    public void run() {

                    }
                });
            }
        };
        timer.schedule(timerTask,0, 5000);

        return SetLevel;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private int checkConnection(){
        int speedLevel = 0;
        ConnectivityManager cm = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
//
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
//        boolean isConnected = activeNetwork != null &&
//                activeNetwork.isConnectedOrConnecting();
        boolean isWifi = activeNetwork.getType() == ConnectivityManager.TYPE_WIFI;
        boolean isHP = activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE;

        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        //should check null because in airplane mode it will be null
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        int downSpeed = nc.getLinkDownstreamBandwidthKbps();
        int upSpeed = nc.getLinkUpstreamBandwidthKbps();

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int numberOfLevels = 5;
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int level = WifiManager.calculateSignalLevel(wifiInfo.getRssi(), numberOfLevels);

        if (isHP){
            speedLevel = switchConnection(downSpeed);
            Log.d(CHECKCONNECTION, " mobile here " + speedLevel);
        } else if (isWifi){
            speedLevel = switchCase(level, wifiInfo, downSpeed);
            Log.d(CHECKCONNECTION, " wifi  here" + speedLevel);
        }

        return speedLevel;
    }

    private int switchConnection(int something){
        int speedLevel = 0;
        Log.d(CHECKCONNECTION, " mobile " + (something));
        if (something < 250){
            speedLevel = 0;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ActivityVideoChat.this, "bad bad", Toast.LENGTH_SHORT).show();
                }
            });
            Log.d(CHECKCONNECTION, " very poor");
        } else if (something <= 10000){
            speedLevel = 0;
            Log.d(CHECKCONNECTION, " Poor");
        } else if (something < 35000) {
            speedLevel = 300;
            Log.d(CHECKCONNECTION, " moderate");
        } else if (something < 80000) {
            speedLevel = 500;
            Log.d(CHECKCONNECTION, " Good");
        } else if (something > 80000) {
            speedLevel = 700;
            Log.d(CHECKCONNECTION, " Excelent");
        }

        return speedLevel;
    }

    private int switchCase(int level, WifiInfo wifiInfo, int something){
        int speedLevel = 0;
        Log.d(CHECKCONNECTION, " wifi " + (something));
        switch (level)
        {
            case 0:
                speedLevel = 0;
                Log.d(CHECKCONNECTION, "No Connection" + wifiInfo.getRssi() + " dan " + level);
                break;

            case 1:
                Log.d(CHECKCONNECTION,"Very Poor " + wifiInfo.getRssi());
                speedLevel = 0;
                break;

            case 2:
                Log.d(CHECKCONNECTION,"Poor" + wifiInfo.getRssi());
                speedLevel = 100;
                break;

            case 3:
                Log.d(CHECKCONNECTION,"Moderate"+ wifiInfo.getRssi());
                speedLevel = 200;
                break;

            case 4:
                Log.d(CHECKCONNECTION,"Good"+ wifiInfo.getRssi() + " dan dan "+ level);
                speedLevel = 500;
                break;

            case 5:
                Log.d(CHECKCONNECTION,"Excellent"+ wifiInfo.getRssi());
                speedLevel = 700;
                break;
        }
        return speedLevel;
    }
}

//    private NBMMediaConfiguration setNbmWebRTCPeer(){
//        final Handler timedHandler = new Handler();
//        Timer timer = new Timer();
//        TimerTask timerTask = new TimerTask() {
//            @Override
//            public void run() {
//                timedHandler.post(new Runnable() {
//                    @Override
//                    public void run() {
//                        peerConnectionParameters = new NBMMediaConfiguration(
//                                NBMMediaConfiguration.NBMRendererType.OPENGLES,
//                                NBMMediaConfiguration.NBMAudioCodec.OPUS, 0,
//                                NBMMediaConfiguration.NBMVideoCodec.VP8, checkConnection(),
//                                new NBMMediaConfiguration.NBMVideoFormat(240, 320, PixelFormat.RGB_888, 4),
//                                NBMMediaConfiguration.NBMCameraPosition.FRONT);
//
//                    }
//                });
//            }
//        };
//        timer.schedule(timerTask,0, 60000);
//        return peerConnectionParameters;
//    }