## 'ps5_autoloader' folder

Put payloads in it. Then put this folder to `/mnt/USB?/` or `/data/`. Or implement it into BD disc root path. It could read `/mnt/disc/ps5_autoloader` too.

## 'autoload.txt' file

It's the script file to define payloads to run automatically by Autoloader. Edit this file according to the example in it.

### Example
```
!5000
ftpsrv-ps5-0.18.3.elf
!1000
shadowmountplus-1.6test8-fix1.elf
!3000
kstuff-lite-1.03.elf
```

## Credits
**[drakmor](https://github.com/drakmor):**
* [ftpsrv](https://github.com/drakmor/ftpsrv)
* [kstuff-lite](https://github.com/drakmor/kstuff-lite)
* [shadowmountplus](https://github.com/drakmor/ShadowMountPlus)

**[EchoStretch](https://github.com/EchoStretch):**
* [kstuff-lite](https://github.com/EchoStretch/kstuff-lite)

**[John Törnblom](https://github.com/john-tornblom):**
* [ftpsrv](https://github.com/ps5-payload-dev/ftpsrv)