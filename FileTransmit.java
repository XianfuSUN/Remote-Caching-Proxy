/**
 * Interface for server to implement
 */

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.io.IOException;

/**
 * Define an interface for file transmission
 * All of the remote methods defined here should be
 * realized first through a tempfile, in case it 
 * locked the main file too long because of latency
 */
public interface FileTransmit extends Remote {
    //create a new threads to do the transmission
    public byte[] transfer(String path, int start, int size, long serveid) throws RemoteException;
    //push a series bytes of content back to the server
    public int push(String path, byte[]content, int size, long start, long service_id) throws RemoteException;
    //connect to the server
    public long connect() throws RemoteException;
    //disconnect to the server
    public void disconnect(long server_id) throws RemoteException;
   // check if file exists
    public Boolean exists(String pathname) throws RemoteException;
    //delete file
    public Boolean delete(String pathname) throws RemoteException, IOException;
    //get file information
    public int[] getFileInfo(String pathname) throws RemoteException;
}