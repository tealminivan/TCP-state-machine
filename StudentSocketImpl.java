import java.net.*;
import java.io.*;
import java.util.Timer;

class StudentSocketImpl extends BaseSocketImpl {

    private Demultiplexer D;
    private Timer tcpTimer;
    private TCPPacket lastPkt1; // last non-ack
    private TCPPacket lastPkt2; // last ack
    private InetAddress locAddress;

    private int currentState;
    private int locAck;
    private int locSeq;
    private int locPort;
    private int count = 1;

    // All of the states as integers to print transitions
    private static final int LISTEN = 0;
    private static final int CLOSED = 1;
    private static final int SYN_SENT = 2;
    private static final int SYN_RCVD = 3;
    private static final int ESTABLISHED = 4;
    private static final int FIN_WAIT_1 = 5;
    private static final int FIN_WAIT_2 = 6;
    private static final int CLOSING = 7;
    private static final int CLOSE_WAIT = 8;
    private static final int LAST_ACK = 9;
    private static final int TIME_WAIT = 10;


StudentSocketImpl(Demultiplexer D) {
    this.D = D;
    currentState = CLOSED;
  }

// Send Wrapper method
public void sendPacketWrapper(boolean prePack, TCPPacket thisPkt, int source, int dest, int seqNumP, int ackNumP, boolean first, boolean second, boolean third, InetAddress sendAddress){

    TCPPacket synpack;

    if(currentState == CLOSED && count > 0){
        notifyAll();
        return;
    }
    if(prePack){
        System.out.println("XXX RESENDING PACKET");
    }

    count++;

    if(prePack){
        synpack = thisPkt;
    }
    else{
        synpack = new TCPPacket(source, dest, seqNumP, ackNumP, first, second, third, 30, null);
    }

    //send the packet
    TCPWrapper.send(synpack, sendAddress);

    //send the packet and start a retransmission timer
    if (!synpack.ackFlag || synpack.synFlag){
        lastPkt1 = synpack;
        createTimerTask(1000);
    }
    else{
        lastPkt2 = synpack;
    }
}


  /**
   * Connects this socket to the specified port number on the specified host.
   *
   * @param      address   the IP address of the remote host.
   * @param      port      the port number.
   * @exception  IOException  if an I/O error occurs when attempting a
   *               connection.
   */
public synchronized void connect(InetAddress address, int port) throws IOException{
    //Initialize state and send syn packet to waiting server
    locAddress = address;
    count --;

    localport = D.getNextAvailablePort();

    D.registerConnection(address, this.localport, port, this);
    sendPacketWrapper(false, lastPkt1, this.localport, port, 0, 0, false, true, false, locAddress);


    currentState = changeState(CLOSED, SYN_SENT);

    while (currentState != ESTABLISHED){
      try{
       wait();
      }
      catch(Exception e){
         e.printStackTrace();
      }
    }
  }



  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
public synchronized void receivePacket(TCPPacket p){

    this.notifyAll();

      if (currentState == LISTEN) {

          if (!p.ackFlag && p.synFlag) {

              locSeq = p.seqNum;
              locAddress = p.sourceAddr;
              locAck = p.ackNum;

              sendPacketWrapper(false, lastPkt1, localport, p.sourcePort, locAck, locSeq+1, true, true, false, locAddress);
              currentState = changeState(currentState, SYN_RCVD);

              //change the socket type
              try {
                  D.unregisterListeningSocket(localport, this);
                  D.registerConnection(locAddress, localport, p.sourcePort, this);
              } catch (IOException e) {
                  e.printStackTrace();
              }
          }
      }


      else if(currentState==SYN_SENT) {
          //resend if there is a dropped SYN and ACK
          if (p.synFlag && p.ackFlag) {

              tcpTimer.cancel();
              tcpTimer = null;

              locSeq = p.seqNum;
              locAddress = p.sourceAddr;
              locPort = p.sourcePort;

              sendPacketWrapper(false, lastPkt1, localport, locPort, -2, locSeq+1, true, false, false, locAddress);
              locPort = p.sourcePort;
              currentState = changeState(currentState, ESTABLISHED);
          }
      }


      else if(currentState==SYN_RCVD) {

        if (p.ackFlag) {
            tcpTimer.cancel(); //cancel the timer for SYN and ACK
            tcpTimer = null;
            locPort = p.sourcePort;
            currentState = changeState(currentState, ESTABLISHED);
        } else if (p.synFlag) {
            sendPacketWrapper(true, lastPkt1, 0, 0, 0, 0, false, false, false, locAddress);
        }
    }


      else if(currentState==ESTABLISHED) {
        // indicated dropped ack, and resends it
        if (p.finFlag) {

            locSeq = p.seqNum;
            locAddress = p.sourceAddr;
            locPort = p.sourcePort;

            sendPacketWrapper(false, lastPkt1, localport, locPort, -2, locSeq+1, true, false, false, locAddress);
            currentState = changeState(currentState, CLOSE_WAIT);
        } else if (p.ackFlag && p.synFlag) {
            sendPacketWrapper(false, lastPkt2, localport, locPort, -2, locSeq+1, true, false, false, locAddress);
        }
    }


      else if(currentState==FIN_WAIT_1) {
        if (p.ackFlag) {
            if (p.synFlag) {
                sendPacketWrapper(true, lastPkt2, 0, 0, 0, 0, false, false, false, locAddress);
            } else {
                //ack for fin
                currentState = changeState(currentState, FIN_WAIT_2);
                tcpTimer.cancel();
                tcpTimer = null;
            }
        }
        if (p.finFlag) {
            //change to closing state
            locSeq = p.seqNum;
            locAddress = p.sourceAddr;
            locAck = p.ackNum;
            locPort = p.sourcePort;

            sendPacketWrapper(false, lastPkt1, localport, locPort, -2, locSeq+1, true, false, false, locAddress);

            currentState = changeState(currentState, CLOSING);
        }
    }



      else if(currentState==FIN_WAIT_2) {
        if (p.finFlag) {
            locAck = p.ackNum;
            locPort = p.sourcePort;
            sendPacketWrapper(false, lastPkt1, localport, locPort, -2, locSeq+1, true, false, false, locAddress);
            currentState = changeState(currentState, TIME_WAIT);

            createTimerTask(30000); //30 second timer
        }
    }


      else if (currentState==LAST_ACK) {
          //indicates a dropped ack
          if (p.ackFlag) {
              tcpTimer.cancel();
              tcpTimer = null;
              currentState = changeState(currentState, TIME_WAIT);
              createTimerTask(30000); //wait 30 seconds
          }
          if (p.finFlag) {
              sendPacketWrapper(true, lastPkt2, 0, 0, 0, 0, false, false, false, locAddress);
          }
      }


      else if(currentState==CLOSE_WAIT) {
          //resend the dropped ack
          if (p.finFlag) {
              sendPacketWrapper(true, lastPkt2, 0, 0, 0, 0, false, false, false, locAddress);
          }
      }


      else if(currentState==TIME_WAIT) {
          try {
              if (p.finFlag) {
                  sendPacketWrapper(true, lastPkt2, 0, 0, 0, 0, false, false, false, locAddress);
              }
          } catch (Exception e) {
              e.printStackTrace();
          }
      }

      else if(currentState==CLOSING) {

        if (p.finFlag) {
              sendPacketWrapper(true, lastPkt2, 0, 0, 0, 0, false, false, false, locAddress);
          }

        else if (p.ackFlag) {
            tcpTimer.cancel();
            tcpTimer = null;
            currentState = changeState(currentState, TIME_WAIT);
            createTimerTask(30000);//wait 30 seconds
        }
    }

  }


  /**
   * Waits for an incoming connection to arrive to connect this socket to
   * Ultimately this is called by the application calling
   * ServerSocket.accept(), but this method belongs to the Socket object
   * that will be returned, not the listening ServerSocket.
   * Note that localport is already set prior to this being called.
   */
public synchronized void acceptConnection() throws IOException {

    D.registerListeningSocket(this.localport, this);
    currentState = changeState(CLOSED, LISTEN);

    //thread sleeps until the state is ESTABLISHED
    while (currentState != ESTABLISHED) {
      try {
        wait();
      }
      catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }


public InputStream getInputStream() throws IOException {
    return null;
  }

public OutputStream getOutputStream() throws IOException {
    return null;
  }


/**
* create TCPTimerTask instance, handling tcpTimer creation
* @param delay time in milliseconds before call
*
*/
private void createTimerTask(long delay){
    if(tcpTimer == null)
      tcpTimer = new Timer(false);
    new TCPTimerTask(tcpTimer, delay, this, null);
}


/**
* handle timer expiration (called by TCPTimerTask)
* @param ref Generic reference that can be used by the timer to return
* information.
*/
public synchronized void handleTimer(Object ref){

    tcpTimer.cancel();
    tcpTimer = null;

    //this must run only once
    if(currentState == TIME_WAIT){
      try {
        currentState = changeState(currentState, CLOSED);
      }
      catch (Exception e) {
        notifyAll();
      }

      notifyAll();


      try {
           D.unregisterConnection(locAddress, localport, locPort, this);
      }
      catch (Exception e) {
        e.printStackTrace();
      }
    }

    //resend the packet due to an ack not being transmitted
    else{
      sendPacketWrapper(true, lastPkt1, 0, 0, 0, 0, false, false, false, locAddress);
    }
  }


private String stateToString(int s){
        if (s == 0){
            return "LISTEN";
        }
        else if (s == 1){
            return "CLOSED";
        }
        else if (s == 2){
            return "SYN_SENT";
        }
        else if (s == 3){
            return "SYN_RCVD";
        }
        else if (s == 4){
            return "ESTABLISHED";
        }
        else if (s == 5){
            return "FIN_WAIT_1";
        }
        else if (s == 6){
            return "FIN_WAIT_2";
        }
        else if (s == 7){
            return "CLOSING";
        }
        else if (s == 8){
            return "CLOSE_WAIT";
        }
        else if (s == 9){
            return "LAST_ACK";
        }
        else if (s == 10){
            return "TIME_WAIT";
        }
        else{
            return "Invalid State";
        }
    }

//print out state transition
public int changeState(int before, int now) {
    System.out.println("!!! " + stateToString(before) + "->" + stateToString(now));
    return now;
  }


public int returnState(boolean currState){
        if(currState){
            return currentState;
        }
        else{
            return CLOSED;
        }
    }


/**
 * Closes this socket.
 *
 * @exception  IOException  if an I/O error occurs when closing this socket.
 */
public synchronized void close() {
    //closes the connection
    sendPacketWrapper(false, lastPkt1, this.localport, this.locPort, locAck, locSeq+1, false, false, true, locAddress);
    if(currentState == ESTABLISHED){
        currentState = changeState(currentState, FIN_WAIT_1);
    }
    else if(currentState == CLOSE_WAIT){
        currentState = changeState(currentState, LAST_ACK);
    }

    try{
        //create a new thread that waits until connection closes
        backgroundThread newThread = new backgroundThread(this);
        newThread.run();
    }
    catch (Exception e){
        e.printStackTrace();
    }
}
}


class backgroundThread implements Runnable{

      public StudentSocketImpl waitToClose;
      public backgroundThread(StudentSocketImpl here) {
        this.waitToClose = here;
      }

      public void run(){
        while (waitToClose.returnState(true) != waitToClose.returnState(false)){
          try {
            waitToClose.wait();
          }
          catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
}


