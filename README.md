# BD-UN-JB-Poops

**Note : YOU NEED ALREADY JAILBROKEN PS5**

BD-UN-JB-Poops is [BD-UN-JB](https://github.com/Gezine/BD-UN-JB) chained with [Poops-PS5-Java](https://github.com/jaigaresc/Poops-PS5-Java).

PS5_autoloader is not implemented yet. I'm working on it.

It can only be used on already jailbroken PS5 upto 12.00 firmware.   
It supports on-screen logging~~ and network logging~~.  

Send bdj_unpatch.elf to elfldr to unpatch BD-J.  
bdj_unpatch.elf will backup existing bdjstack.jar to bdjstack.jar.bak just in case.  

Then burn BD-UN-JB-Poops iso and run.  

**DO NOT REINSTALL FW, IT WILL WIPE THE PATCH AND LOSE BD-JB**

---

RemoteLogger server is listening on port 18194.  
Use log_client.py to get the log.  
I recommend first running the log_client.py then starting the BD-J app.  

~~RemoteJarLoader server is listening on port 9025.  ~~
~~Use jar_client.py to send the jar file.  ~~
~~You can use any other TCP payload sender too.  ~~
~~Don't forget to set Main-Class in manifest.txt.~~

---

Use john-tornblom's **[bdj-sdk](https://github.com/john-tornblom/bdj-sdk/)** and **[ps5-payload-sdk](https://github.com/ps5-payload-dev/sdk/)** for compiling.  

---

### Credits

* **[BD-UN-JB](https://github.com/Gezine/BD-UN-JB)** — BD-UN-JB for basics.  
* **[TheFlow](https://github.com/theofficialflow)** — BD-JB documentation & native code execution sources.  
* **[hammer-83](https://github.com/hammer-83)** — PS5 Remote JAR Loader reference.  
* **[john-tornblom](https://github.com/john-tornblom)** — [BDJ-SDK](https://github.com/john-tornblom/bdj-sdk) and [ps5-payload-sdk](https://github.com/ps5-payload-dev/sdk/) used for compilation.  
* **[kuba--](https://github.com/kuba--)** — [zip](https://github.com/kuba--/zip) used for bdj_unpatch elf payload.  
* **[Poops-PS5-Java](https://github.com/jaigaresc/Poops-PS5-Java)** — used for jar payload.

---


































