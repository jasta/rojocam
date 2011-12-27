package org.devtcg.rojocam;

import org.teleal.cling.UpnpService;
import org.teleal.cling.UpnpServiceImpl;
import org.teleal.cling.android.AndroidUpnpServiceConfiguration;
import org.teleal.cling.android.AndroidWifiSwitchableRouter;
import org.teleal.cling.protocol.ProtocolFactory;
import org.teleal.cling.registry.Registry;
import org.teleal.cling.registry.RegistryListener;
import org.teleal.cling.support.igd.PortMappingListener;
import org.teleal.cling.support.model.PortMapping;
import org.teleal.cling.transport.Router;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Provides UPnP services (NAT port mapping). This class is incredibly
 * inflexible, offering only the most basic features we need (single port
 * mapping and unmapping on demand). Currently no error reporting is provided...
 */
public class UPnPPortMapper {
    private static final int SCAN_TIMEOUT = 5000;

    private WeakReference<Context> mContext;

    private InetAddress mLocalAddress;
    private UpnpService mUpnpService;

    private String mDescription;
    private int mPort;

    private WifiManager mWifiMan;
    private ConnectivityManager mConnMan;

    /**
     * Create a port mapper handle and attempt to map the specified port. This
     * begins an asynchronous operation to scan the network and ultimately try
     * to add the port mapping to any InternetGatewayDevices found.
     *
     * @return True if necessary and an attempt is underway; false otherwise.
     * @throws IOException
     */
    public static UPnPPortMapper mapPortIfNecessary(Context context, String description, int port)
            throws IOException {
        UPnPPortMapper mapper = new UPnPPortMapper(context, description, port);
        if (mapper.needsPortMapping()) {
            mapper.mapPorts();
            return mapper;
        } else {
            return null;
        }
    }

    private UPnPPortMapper(Context context, String description, int port) {
        mContext = new WeakReference<Context>(context);
        mDescription = description;
        mPort = port;

        mWifiMan = (WifiManager)getContext().getSystemService(Context.WIFI_SERVICE);
        mConnMan = (ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private final Context getContext() {
        return mContext.get();
    }

    private WifiManager getWifiManager() {
        return mWifiMan;
    }

    private ConnectivityManager getConnectivityManager() {
        return mConnMan;
    }

    private boolean needsPortMapping() throws IOException {
        return getDefaultRouteLocalAddress().isSiteLocalAddress();
    }

    private void mapPorts() throws IOException {
        if (mUpnpService != null) {
            throw new IllegalStateException();
        }

        String internalIp = getDefaultRouteLocalAddress().getHostAddress();
        PortMapping mapping = new PortMapping(mPort, internalIp, PortMapping.Protocol.TCP, mDescription);
        mUpnpService = new MyUpnpServiceImpl(new PortMappingListener(mapping));
        mUpnpService.getControlPoint().search(SCAN_TIMEOUT);
    }

    /**
     * Disable UPnP and remove any port mappings we registered. This should be
     * called during application shutdown.
     */
    public void unmapPorts() {
        mUpnpService.shutdown();
    }

    /**
     * Determine the source IP (our IP) of our route to www.google.com:80. If
     * the user has layers of indirection to access the Internet, it is our
     * assumption that this function will return an address in the RFC1918
     * private space; otherwise, no IGD mappings are necessary.
     *
     * @throws IOException
     */
    private static InetAddress determineDefaultRouteLocalAddress() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("www.google.com", 80));
            return socket.getLocalAddress();
        } finally {
            socket.close();
        }
    }

    private synchronized InetAddress getDefaultRouteLocalAddress() throws IOException {
        if (mLocalAddress == null) {
            mLocalAddress = determineDefaultRouteLocalAddress();
        }

        return mLocalAddress;
    }

    private class MyUpnpServiceImpl extends UpnpServiceImpl {
        public MyUpnpServiceImpl(RegistryListener... listeners) {
            super(new AndroidUpnpServiceConfiguration(getWifiManager()), listeners);
        }

        @Override
        protected Router createRouter(ProtocolFactory protocolFactory, Registry registry) {
            return new AndroidWifiSwitchableRouter(configuration, protocolFactory,
                    getWifiManager(), getConnectivityManager());
        }
    }
}
