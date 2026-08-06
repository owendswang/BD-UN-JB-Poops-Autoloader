# BD-JB5 + Poops + Autoloader

Based on BD-JB5 by Gezine and chained with poops and the old-school autoloader.

### Requirements

PS5 with a working disc drive and firmware version between 4.03 and 12.00.  
You'd need a USB drive to setup the Autoload sequence for the first time.

### Usage

1. Burn the ISO to a BD disc. (DVD won't work.)
2. Put the disc into your PS5.
3. (Optional / First time) Plug in the USB drive formatted as `exfat` with `ps5_autoloader_update.zip` inside.
4. Play the disc on PS5.

If succeeded, the disc player would close automatically and run the Autoloader sequence.
if you got stuck at `triple free` or the `failed and reboot` notification showed up, just reboot and try again. 

### Autoloader

\>\>[Setup Guide](./ps5_autoloader)<<

### Restore bdj_unpatch

`restore_bdj_unpatch` restores the original `bdjstack.jar` from the `.bak` file created by `bdj_unpatch`, replacing the patched JAR without repacking it.

### Build

Use john-tornblom's **[bdj-sdk](https://github.com/john-tornblom/bdj-sdk/)** and **[ps5-payload-sdk](https://github.com/ps5-payload-dev/sdk/)** for compiling.  

### Credits

* **[Gezine](https://github.com/Gezine/BD-JB5)** — BD-JB5 for basics.
* **[TheFlow](https://github.com/theofficialflow)** — BD-JB documentation & native code execution sources & original NetCtrol exploit code.
* **[MassZero0](https://github.com/MassZero0)** — Reimplementation TheFloW's NetCtrol exploit code to John Tornblom's BDJ-SDK and chained it with debug patching and elfldr. He nailed this before me. [NetPoops-PS5](https://github.com/MassZero0/NetPoops-PS5)
* **[hammer-83](https://github.com/hammer-83)** — PS5 Remote JAR Loader reference.
* **[john-tornblom](https://github.com/john-tornblom)** — [BDJ-SDK](https://github.com/john-tornblom/bdj-sdk) and [ps5-payload-sdk](https://github.com/ps5-payload-dev/sdk/) used for compilation.
* **[kuba--](https://github.com/kuba--)** — [zip](https://github.com/kuba--/zip) Used for bdj_unpatch and ps5_autoload elf payload.
* **[jaigaresc](https://github.com/jaigaresc/Poops-PS5-Java)** — First port of poops.jar from javascript.
* **[itsPLK](https://github.com/itsPLK/ps5_y2jb_autoloader):** Autoloader theory before v0.4.
* **[BenNoxXD](https://github.com/BenNoxXD/PS5-BDJ-HEN-loader):** Method to close disc player.
* **[ufm42](https://github.com/ufm42)** - [kexp](https://github.com/ufm42/kexp) used for PS5 post JB all-in-one shellcode
* **[kuba--](https://github.com/kuba--)** — [zip](https://github.com/kuba--/zip) used for bdj_unpatch elf payload.  

### Disclaimer

This tool is provided as-is for research and development purposes only.  
Use at your own risk.  
The developers are not responsible for any damage, data loss, or other consequences resulting from the use of this software.  
