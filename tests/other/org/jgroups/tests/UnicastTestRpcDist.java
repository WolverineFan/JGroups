
package org.jgroups.tests;

import org.jgroups.*;
import org.jgroups.blocks.*;
import org.jgroups.jmx.JmxConfigurator;
import org.jgroups.protocols.UNICAST;
import org.jgroups.util.Util;
import org.jgroups.util.RspList;
import org.jgroups.util.Rsp;

import javax.management.MBeanServer;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Vector;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Tests the UNICAST by invoking unicast RPCs between a sender and a receiver. Mimicks the DIST mode in Infinispan
 *
 * @author Bela Ban
 */
public class UnicastTestRpcDist extends ReceiverAdapter {
    private JChannel                  channel;
    private Address                   local_addr;
    private RpcDispatcher             disp;
    static final String               groupname="UnicastTest-Group";
    private final Collection<Address> members=new ArrayList<Address>();
    private int                       print=1000;


    // ============ configurable properties ==================
    private boolean sync=false, oob=false;
    private int num_threads=1;
    private int num_msgs=50000, msg_size=1000;
    private int anycast_count=1;
    private static final double READ_PERCENTAGE=0.8; // 80% reads, 20% writes
    // =======================================================



    private static final Method[] METHODS=new Method[10];

    private static final short RECEIVE           =  0;
    private static final short START             =  1;
    private static final short SET_OOB           =  2;
    private static final short SET_SYNC          =  3;
    private static final short SET_NUM_MSGS      =  4;
    private static final short SET_NUM_THREADS   =  5;
    private static final short SET_MSG_SIZE      =  6;
    private static final short SET_ANYCAST_COUNT =  7;
    private static final short GET               =  8;
    private static final short PUT               = 9;

    private final AtomicInteger COUNTER=new AtomicInteger(1);
    private byte[] GET_RSP=new byte[msg_size];


    long tot=0;
    int num_reqs=0;

    static {
        try {
            METHODS[RECEIVE]           = UnicastTestRpcDist.class.getMethod("receiveData", long.class, byte[].class);
            METHODS[START]             = UnicastTestRpcDist.class.getMethod("startTest");
            METHODS[SET_OOB]           = UnicastTestRpcDist.class.getMethod("setOOB", boolean.class);
            METHODS[SET_SYNC]          = UnicastTestRpcDist.class.getMethod("setSync", boolean.class);
            METHODS[SET_NUM_MSGS]      = UnicastTestRpcDist.class.getMethod("setNumMessages", int.class);
            METHODS[SET_NUM_THREADS]   = UnicastTestRpcDist.class.getMethod("setNumThreads", int.class);
            METHODS[SET_MSG_SIZE]      = UnicastTestRpcDist.class.getMethod("setMessageSize", int.class);
            METHODS[SET_ANYCAST_COUNT] = UnicastTestRpcDist.class.getMethod("setAnycastCount", int.class);
            METHODS[GET]               = UnicastTestRpcDist.class.getMethod("get");
            METHODS[PUT]               = UnicastTestRpcDist.class.getMethod("put", byte[].class);
        }
        catch(NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }


    public void init(String props, String name) throws Exception {
        channel=new JChannel(props);
        if(name != null)
            channel.setName(name);
        disp=new RpcDispatcher(channel, null, this, this);
        disp.setMethodLookup(new MethodLookup() {
            public Method findMethod(short id) {
                return METHODS[id];
            }
        });
        disp.setRequestMarshaller(new CustomMarshaller());
        channel.connect(groupname);
        local_addr=channel.getAddress();

        try {
            MBeanServer server=Util.getMBeanServer();
            JmxConfigurator.registerChannel(channel, server, "jgroups", channel.getClusterName(), true);
        }
        catch(Throwable ex) {
            System.err.println("registering the channel in JMX failed: " + ex);
        }
    }

    void stop() {
        if(disp != null)
            disp.stop();
        Util.close(channel);
    }

    public void viewAccepted(View new_view) {
        System.out.println("** view: " + new_view);
        members.clear();
        members.addAll(new_view.getMembers());
    }

    // =================================== callbacks ======================================

    public Results startTest() throws Throwable {
        System.out.println("invoking " + num_msgs + " RPCs of " + Util.printBytes(msg_size)
                + ", sync=" + sync + ", oob=" + oob);

        RequestOptions options=new RequestOptions(sync? Request.GET_ALL : Request.GET_NONE, 0);
        if(sync) options.setFlags(Message.DONT_BUNDLE);
        if(oob) options.setFlags(Message.OOB);

        Invoker[] invokers=new Invoker[num_threads];
        for(int i=0; i < invokers.length; i++) {
            invokers[i]=new Invoker(members, options, num_msgs / num_threads);
        }

        long start=System.currentTimeMillis();
        for(Invoker invoker: invokers)
            invoker.start();

        for(Invoker invoker: invokers)
            invoker.join();

        long total_time=System.currentTimeMillis() - start;

        Results results=new Results();
        results.time=total_time;
        return results;
    }


    public void setOOB(boolean oob) {
        this.oob=oob;
        System.out.println("oob=" + oob);
    }

    public void setSync(boolean val) {
        this.sync=val;
        System.out.println("sync=" + sync);
    }

    public void setNumMessages(int num) {
        num_msgs=num;
        System.out.println("num_msgs = " + num_msgs);
        print=num_msgs / 10;
    }

    public void setNumThreads(int num) {
        num_threads=num;
        System.out.println("num_threads = " + num_threads);
    }

    public void setMessageSize(int num) {
        msg_size=num;
        System.out.println("msg_size = " + msg_size);
    }

    public void setAnycastCount(int num) {
        anycast_count=num;
        System.out.println("anycast_count = " + anycast_count);
    }


   /* // received by another node in the cluster
    public void config(ConfigOptions options) {
        this.oob=options.oob;
        this.sync=options.sync;
        this.anycasting=options.anycasting;
        this.num_threads=options.num_threads;
        this.num_msgs=options.num_msgs;
        this.msg_size=options.msg_size;
        this.anycast_count=options.anycast_count;
    }*/


    public byte[] get(long key) {
        return GET_RSP;
    }


    public void put(long key, byte[] val) {
        
    }


/*    public long receiveData(long value, byte[] buffer) {
        long diff=System.currentTimeMillis() - value;
        tot+=diff;
        num_reqs++;

        long new_val=current_value.incrementAndGet();
        total_bytes.addAndGet(buffer.length);
        if(print > 0 && new_val % print == 0)
            System.out.println("received " + current_value);


        if(new_val >= num_values) {
            stop=System.currentTimeMillis();
            long total_time=stop - start;
            long msgs_per_sec=(long)(num_values / (total_time / 1000.0));
            double throughput=total_bytes.get() / (total_time / 1000.0);
            System.out.println("\n-- received " + num_values + " messages in " + total_time +
                    " ms (" + msgs_per_sec + " messages/sec, " + Util.printBytes(throughput) + " / sec)");
            double time_per_req=(double)tot / num_reqs;
            System.out.println("received " + num_reqs + " requests in " + tot + " ms, " + time_per_req +
                    " ms / req (only request)\n");

            started=false;
            current_value.set(0); // first value to be received
            total_bytes.set(0);
            tot=0; num_reqs=0;
        }
        return System.currentTimeMillis();
    }*/

    // ================================= end of callbacks =====================================


    public void eventLoop() throws Throwable {
        int c;

        while(true) {
            c=Util.keyPress("[1] Send msgs [2] Print view [3] Print conns " +
                    "[4] Trash conn [5] Trash all conns" +
                    "\n[6] Set sender threads (" + num_threads + ") [7] Set num msgs (" + num_msgs + ") " +
                    "[8] Set msg size (" + Util.printBytes(msg_size) + ")" +
                    " [9] Set anycast count (" + anycast_count + ")" +
                    "\n[o] Toggle OOB (" + oob + ") [s] Toggle sync (" + sync + ")" +
                    "\n[q] Quit\n");
            switch(c) {
                case -1:
                    break;
                case '1':
                    try {
                        startBenchmark();
                    }
                    catch(Throwable t) {
                        System.err.println(t);
                    }
                    break;
                case '2':
                    printView();
                    break;
                case '3':
                    printConnections();
                    break;
                case '4':
                    removeConnection();
                    break;
                case '5':
                    removeAllConnections();
                    break;
                case '6':
                    setSenderThreads();
                    break;
                case '7':
                    setNumMessages();
                    break;
                case '8':
                    setMessageSize();
                    break;
                case '9':
                    setAnycastCount();
                    break;
                case 'o':
                    boolean new_value=!oob;
                    disp.callRemoteMethods(null, new MethodCall(SET_OOB, new_value), RequestOptions.SYNC);
                    break;
                case 's':
                    boolean new_val=!sync;
                    disp.callRemoteMethods(null, new MethodCall(SET_SYNC, new_val), RequestOptions.SYNC);
                    break;
                case 'q':
                    channel.close();
                    return;
                case '\n':
                case '\r':
                    break;
                default:
                    break;
            }
        }
    }

    private void printConnections() {
        UNICAST unicast=(UNICAST)channel.getProtocolStack().findProtocol(UNICAST.class);
        System.out.println("connections:\n" + unicast.printConnections());
    }

    private void removeConnection() {
        Address member=getReceiver();
        if(member != null) {
            UNICAST unicast=(UNICAST)channel.getProtocolStack().findProtocol(UNICAST.class);
            unicast.removeConnection(member);
        }
    }

    private void removeAllConnections() {
        UNICAST unicast=(UNICAST)channel.getProtocolStack().findProtocol(UNICAST.class);
        unicast.removeAllConnections();
    }


    /** Kicks off the benchmark on all cluster nodes */
    void startBenchmark() throws Throwable {
        if(num_threads > 1 && num_msgs % num_threads != 0) {
            System.err.println("num_msgs (" + num_msgs + " ) has to be divisible by num_threads (" + num_threads + ")");
            return;
        }

        RspList responses=disp.callRemoteMethods(null, new MethodCall(START), new RequestOptions(Request.GET_ALL, 0));
        System.out.println("Results:");
        for(Map.Entry<Address,Rsp> entry: responses.entrySet()) {
            Address mbr=entry.getKey();
            Rsp rsp=entry.getValue();
            System.out.println(mbr + ": " + rsp.getValue());
        }
    }
    

    void setSenderThreads() throws Exception {
        int threads=Util.readIntFromStdin("Number of sender threads: ");
        disp.callRemoteMethods(null, new MethodCall(SET_NUM_THREADS, threads), RequestOptions.SYNC);
    }

    void setNumMessages() throws Exception {
        int tmp=Util.readIntFromStdin("Number of RPCs: ");
        disp.callRemoteMethods(null, new MethodCall(SET_NUM_MSGS, tmp), RequestOptions.SYNC);
    }

    void setMessageSize() throws Exception {
        int tmp=Util.readIntFromStdin("Message size: ");
        disp.callRemoteMethods(null, new MethodCall(SET_MSG_SIZE, tmp), RequestOptions.SYNC);
    }

    void setAnycastCount() throws Exception {
        int tmp=Util.readIntFromStdin("Anycast count: ");
        View view=channel.getView();
        if(tmp > view.size()) {
            System.err.println("anycast count must be smaller or equal to the view size (" + view + ")\n");
            return;
        }
        disp.callRemoteMethods(null, new MethodCall(SET_ANYCAST_COUNT, tmp), RequestOptions.SYNC);
    }

    /*void populateAnycastList(View view) {
        anycast_mbrs.clear();
        Vector<Address> mbrs=view.getMembers();
        int index=mbrs.indexOf(local_addr);
        for(int i=index + 1; i < index + 1 + anycast_count; i++) {
            int new_index=i % mbrs.size();
            anycast_mbrs.add(mbrs.get(new_index));
        }
        System.out.println("local_addr=" + local_addr + ", anycast_mbrs = " + anycast_mbrs);
    }*/

    void printView() {
        System.out.println("\n-- view: " + channel.getView() + '\n');
        try {
            System.in.skip(System.in.available());
        }
        catch(Exception e) {
        }
    }


    /** Picks the next member in the view */
    private Address getReceiver() {
        try {
            Vector<Address> mbrs=channel.getView().getMembers();
            int index=mbrs.indexOf(local_addr);
            int new_index=index + 1 % mbrs.size();
            return mbrs.get(new_index);
        }
        catch(Exception e) {
            System.err.println("UnicastTest.getReceiver(): " + e);
            return null;
        }
    }

    private class Invoker extends Thread {
        private final Address             dest;
        private final Collection<Address> dests=new ArrayList<Address>();
        private final int                 number_of_msgs;


        public Invoker(Collection<Address> dests, RequestOptions options, int number_of_msgs) {
            this.dest=null;
            this.dests.addAll(dests);
            this.number_of_msgs=number_of_msgs;
            setName("Invoker-" + COUNTER.getAndIncrement());
        }


        public void run() {
            final byte[] buf=new byte[msg_size];
            Object[] put_args=new Object[]{0};
            MethodCall get_call=new MethodCall(GET);
            MethodCall put_call=new MethodCall(PUT, put_args);
            RequestOptions get_options=new RequestOptions(Request.GET_ALL, 5000, false, null);
            RequestOptions put_options=new RequestOptions(Request.GET_ALL, 5000, true, null);

            for(int i=1; i <= number_of_msgs; i++) {
                boolean get=Util.tossWeightedCoin(READ_PERCENTAGE);

                try {
                    if(get) { // sync GET
                        Address target=pickTarget();
                        Object retval=disp.callRemoteMethod(target, get_call, get_options);
                    }
                    else {    // sync or async (based on value of 'sync') PUT
                        Collection<Address> targets=pickAnycastTargets();
                        put_args[0]=i;
                        RspList responses=disp.callRemoteMethods(targets, put_call, put_options);
                    }
                }
                catch(Throwable throwable) {
                    throwable.printStackTrace();
                }
            }
        }

        private Address pickTarget() {
            return null;
        }

        private Collection<Address> pickAnycastTargets() {
            return null;
        }
    }


    public static class Results {
        long num_gets=0;
        long num_puts=0;
        long time=0;
    }


    static class ConfigOptions {
        private final boolean sync, oob, anycasting;
        private final int num_threads;
        private final int num_msgs, msg_size;
        private final int anycast_count;

        public ConfigOptions(boolean oob, boolean sync, boolean anycasting, int num_threads, int num_msgs, int msg_size, int anycast_count) {
            this.oob=oob;
            this.sync=sync;
            this.anycasting=anycasting;
            this.num_threads=num_threads;
            this.num_msgs=num_msgs;
            this.msg_size=msg_size;
            this.anycast_count=anycast_count;
        }

        public String toString() {
            return "oob=" + oob + ", sync=" + sync + ", anycasting=" + anycasting + ", anycast_count=" + anycast_count +
                    ", num_threads=" + num_threads + ", num_msgs=" + num_msgs + ", msg_size=" + msg_size;
        }
    }


    static class CustomMarshaller implements RpcDispatcher.Marshaller {

        public byte[] objectToByteBuffer(Object obj) throws Exception {
            MethodCall call=(MethodCall)obj;
            ByteBuffer buf;
            switch(call.getId()) {
                case RECEIVE:
                    return intBuffer(RECEIVE, (Integer)call.getArgs()[0]);
                case START:
                    Long long_arg=(Long)call.getArgs()[0];
                    byte[] arg2=(byte[])call.getArgs()[1];
                    buf=ByteBuffer.allocate(Global.BYTE_SIZE + Global.INT_SIZE + Global.LONG_SIZE + arg2.length);
                    buf.put((byte)START).putLong(long_arg).putInt(arg2.length).put(arg2, 0, arg2.length);
                    return buf.array();
                case SET_OOB:
                case SET_SYNC:
                    return booleanBuffer(call.getId(), (Boolean)call.getArgs()[0]);
                case SET_NUM_MSGS:
                case SET_NUM_THREADS:
                case SET_MSG_SIZE:
                case SET_ANYCAST_COUNT:
                    return intBuffer(call.getId(), (Integer)call.getArgs()[0]);
                default:
                    throw new IllegalStateException("method " + call.getMethod() + " not known");
            }
        }



        public Object objectFromByteBuffer(byte[] buffer) throws Exception {
            ByteBuffer buf=ByteBuffer.wrap(buffer);

            byte type=buf.get();
            switch(type) {
                case RECEIVE:
                    int arg=buf.getInt();
                    return new MethodCall(type, arg);
                case START:
                    Long longarg=buf.getLong();
                    int len=buf.getInt();
                    byte[] arg2=new byte[len];
                    buf.get(arg2, 0, arg2.length);
                    return new MethodCall(type, longarg, arg2);
                case SET_OOB:
                case SET_SYNC:
                    return new MethodCall(type, buf.get() == 1);
                case SET_NUM_MSGS:
                case SET_NUM_THREADS:
                case SET_MSG_SIZE:
                case SET_ANYCAST_COUNT:
                    return new MethodCall(type, buf.getInt());
                default:
                    throw new IllegalStateException("type " + type + " not known");
            }
        }

        private static byte[] intBuffer(short type, Integer num) {
            ByteBuffer buf=ByteBuffer.allocate(Global.BYTE_SIZE + Global.INT_SIZE);
            buf.put((byte)type).putInt(num);
            return buf.array();
        }

        private static byte[] longBuffer(short type, Long num) {
            ByteBuffer buf=ByteBuffer.allocate(Global.BYTE_SIZE + Global.LONG_SIZE);
            buf.put((byte)type).putLong(num);
            return buf.array();
        }

        private static byte[] booleanBuffer(short type, Boolean arg) {
            ByteBuffer buf=ByteBuffer.allocate(Global.BYTE_SIZE *2);
            buf.put((byte)type).put((byte)(arg? 1 : 0));
            return buf.array();
        }
    }


    public static void main(String[] args) {
        String props=null;
        String name=null;


        for(int i=0; i < args.length; i++) {
            if("-props".equals(args[i])) {
                props=args[++i];
                continue;
            }
            if("-name".equals(args[i])) {
                name=args[++i];
                continue;
            }
            help();
            return;
        }

        UnicastTestRpcDist test=null;
        try {
            test=new UnicastTestRpcDist();
            test.init(props, name);
            test.eventLoop();
        }
        catch(Throwable ex) {
            ex.printStackTrace();
            if(test != null)
                test.stop();
        }
    }

    static void help() {
        System.out.println("UnicastTestRpc [-props <props>] [-name name]");
    }


}