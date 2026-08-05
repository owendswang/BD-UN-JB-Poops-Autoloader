## 'ps5_autoloader' folder

Put payloads in it. Then put this folder to `/mnt/usb?/` or `/data/`. Or implement it into BD disc root path. It could read `/mnt/disc/ps5_autoloader` too.

### Priority

`/mnt/usb?/ps5_autoloader` -> `/data/ps5_autoloader` -> `/mnt/disc/ps5_autoloader`

## 'autoload.txt' file

It's the script file to define payloads to run automatically by Autoloader. Edit this file according to the example in it.

**Example:**
```
!5000
ftpsrv-ps5-0.21.elf
!1000
shadowmountplus-1.7alpha1.elf
!3000
kstuff-lite-1.09beta.elf
```
Or if you would like itsPLK's Payload Manager to take over the autoload sequence, then setup like below.
```
!3000
pldmgr_v0.5.1.elf
```

## 'ps5_autoloader_update.zip' file
It's able to update `/data/ps5_autoloader` directory with it.  
Wrap anything into `ps5_autoloader_update.zip` and put it into a USB storage device formatted as FAT32 or EXFAT. Plug it to PS5 before you run the BD disc. Everything inside `ps5_autoloader_update.zip` would be extracted to `/data/ps5_autoloader` before the autoloader process.

## Credits
**[drakmor](https://github.com/drakmor):**
* [ftpsrv](https://github.com/drakmor/ftpsrv)
* [kstuff-lite](https://github.com/drakmor/kstuff-lite)
* [shadowmountplus](https://github.com/drakmor/ShadowMountPlus)

**[EchoStretch](https://github.com/EchoStretch):**
* [kstuff-lite](https://github.com/EchoStretch/kstuff-lite)

**[John Törnblom](https://github.com/john-tornblom):**
* [ftpsrv](https://github.com/ps5-payload-dev/ftpsrv)

**[itsPLK](https://github.com/itsPLK):**
* [itsPLK/ps5_y2jb_autoloader](https://github.com/itsPLK/ps5_y2jb_autoloader)
* [itsPLK/ps5_lua_autoloader](https://github.com/itsPLK/ps5_lua_autoloader)
* [itsPLK/ps5-payload-manager](https://github.com/itsPLK/ps5-payload-manager)