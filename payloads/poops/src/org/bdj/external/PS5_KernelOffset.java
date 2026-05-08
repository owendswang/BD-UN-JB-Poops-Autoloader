package org.bdj.external;

import org.bdj.Status;
import java.util.Hashtable;

public class PS5_KernelOffset {

    public static final int FILEDESCENT_SIZE = 0x30;
    public static final int KQ_FDP_OFFSET = 0xA8;
    public static final int PIPE_SIGIO_OFFSET = 0xd8;
    public static final int IN6P_OUTPUTOPTS_OFFSET = 0x120;
    public static final int IP6PO_RHI_RTHDR_OFFSET = 0x70;
    public static final int ROOTVNODE_OFFSET = 0x8;
    public static final int FDT_OFILES_OFFSET = 0x8;
    public static final int P_PID_OFFSET = 0xbc;
    
    private static Hashtable KernelOffsets;
    public static String FW_VERSION;
    
    static {
        initializeOffsets();
    }

    private static void initializeOffsets() {
        KernelOffsets = new Hashtable();
        
        // ALLPROC, SECURITY_FLAGS, ROOTVNODE, KERNEL_PMAP_STORE, GVMSPACE
        addFirmwareOffsets("4.00", 0x27EDCB8, 0x6506474, 0x66E74C0, 0x3257A78, 0x64C3F80);
        addFirmwareOffsets("5.00", 0x291DD00, 0x66466EC, 0x6853510, 0x3398A88, 0x6603FB0);
        addFirmwareOffsets("5.50", 0x291DD00, 0x66466EC, 0x6853510, 0x3394A88, 0x6603FB0);
        addFirmwareOffsets("6.00", 0x2869D20, 0x65968EC, 0x679F510, 0x32E4358, 0x65540F0);
        addFirmwareOffsets("7.00", 0x2859D50, 0xAC8064, 0x30C7510, 0x2E2C848, 0x2E76090);
        addFirmwareOffsets("8.00", 0x2875D50, 0xAC3064, 0x30FB510, 0x2E48848, 0x2EAA090);
        addFirmwareOffsets("9.00", 0x2755D50, 0xD72064, 0x2FDB510, 0x2D28B78, 0x2D8A570);
        addFirmwareOffsets("9.05", 0x2755D50, 0xD73064, 0x2FDB510, 0x2D28B78, 0x2D8A570);
        addFirmwareOffsets("10.00", 0x2765D70, 0xD79064, 0x2FA3510, 0x2CF0EF8, 0x2D52570);
        addFirmwareOffsets("11.00", 0x2875D70, 0xD8C064, 0x30B7510, 0x2E04F18, 0x2E66570);
        addFirmwareOffsets("12.00", 0x2885E00, 0xD83064, 0x30D7510, 0x2E1CFB8, 0x2E7E570);

        // 13.00 is untested
        addFirmwareOffsets("13.00", 0x28C5E00, 0xD99064, 0x3133510, 0x2E74FF8, 0x2ED6570);

        addFirmwareOffsets("13.20", 0x28C5E00, 0xD99064, 0x3133510, 0x2E74FF8, 0x2ED6570);
    }
    
    private static void addFirmwareOffsets(String fw, int ALLPROC, int SECURITY_FLAGS, int ROOTVNODE, int KERNEL_PMAP_STORE, int GVMSPACE) {
        Hashtable offsets = new Hashtable();
        offsets.put("ALLPROC",           new Long(ALLPROC));
        offsets.put("SECURITY_FLAGS",    new Long(SECURITY_FLAGS));
        offsets.put("ROOTVNODE",         new Long(ROOTVNODE));
        offsets.put("KERNEL_PMAP_STORE", new Long(KERNEL_PMAP_STORE));
        offsets.put("GVMSPACE",          new Long(GVMSPACE));
        KernelOffsets.put(fw, offsets);
    }
    
    public static long getOffset(String key) {
        Hashtable offsets;
    
        if (KernelOffsets.containsKey(FW_VERSION)) {
            offsets = (Hashtable) KernelOffsets.get(FW_VERSION);
        } else {
            int major = 0;
            int minor = 0;

            int dotIndex = FW_VERSION.indexOf('.');
            if (dotIndex > 0) {
                major = Integer.parseInt(FW_VERSION.substring(0, dotIndex));
                minor = Integer.parseInt(FW_VERSION.substring(dotIndex + 1));
            } else {
                major = Integer.parseInt(FW_VERSION);
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

            offsets = (Hashtable) KernelOffsets.get(targetFw);
        }
    
        if (offsets == null) {
            throw new RuntimeException("No offsets available for firmware " + FW_VERSION);
        }
        Long offset = (Long) offsets.get(key);
        if (offset == null) {
            throw new RuntimeException("Offset " + key + " not found for firmware " + FW_VERSION); // key, not offset
        }
        return offset.longValue();
    }
    
}