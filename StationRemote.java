import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;

/**
 * EXPERIMENT 1 - remote interface of a railway station (Java RMI).
 *
 * A client never holds a RailwayStation object. It looks the station up in
 * the RMI registry and receives a stub that implements this interface;
 * every method call on the stub is sent over TCP to the station's server,
 * executed there, and the result (or a RemoteException) comes back.
 */
public interface StationRemote extends Remote {

    /** Reply to a ping: who answered, its Lamport time and the server thread. */
    record PingReply(String station, String status, int lamport, String servedBy)
            implements Serializable {
    }

    /** Reply to a message: the station's Lamport time after the receive rule. */
    record MessageReply(String station, int receiveTimestamp, int replyTimestamp)
            implements Serializable {
    }

    PingReply ping() throws RemoteException;

    int getLamportClock() throws RemoteException;

    long getStateVersion() throws RemoteException;

    Map<String, TrackStateReplica.TrackStatus> getTrackState() throws RemoteException;

    /**
     * Delivers a message stamped with the sender's Lamport time. The station
     * applies the receive rule L = max(L, t) + 1 and answers with a reply
     * that carries its own (send) timestamp.
     */
    MessageReply deliverMessage(String sender, int lamportTimestamp, String text)
            throws RemoteException;

    /**
     * Operator console of the railway network, also published in the
     * registry. Lets a remote operator crash and repair stations.
     */
    interface Control extends Remote {

        List<String> stationNames() throws RemoteException;

        String failStation(String name) throws RemoteException;

        String recoverStation(String name) throws RemoteException;
    }
}
