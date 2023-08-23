# SoftVC VITS Singing Voice Conversion Dedicated Server

## Overview
A dedicated Server for So-VITS-SVC. Client: [SoftVC VITS Singing Voice Conversion Java Swing GUI](https://github.com/Redtropig/so-vits-svc-gui). **_(WARNING: CyberSecurity is NOT considered throughout this project. No penetration test was performed. It may be vulnerable.)_**

### **Requirements**
- NVIDIA Graphic Card(s) with CUDA
- Windows OS-dependent
- [**Java Development Kit**](https://www.oracle.com/java/technologies/downloads/) or Java Runtime Environment (deprecated)
- Dependencies needed but NOT included in this Repo: (**have been packed into _[Releases](https://github.com/Redtropig/so-vits-svc-gui/releases)_**)
  - `.\workenv\` (Portable Working-Environment)
  - `.\so-vits-svc-4.1-Stable\pretrain\` (Pretrained Models)
  - `.\so-vits-svc-4.1-Stable\logs\44k\` (So-VITS-SVC Training Logs & Base Models)

### **User Stories**
- Provide remote So-VITS-SVC services for [so-vits-svc-gui](https://github.com/Redtropig/so-vits-svc-gui).
- Reuse the ExecutionAgent class from [so-vits-svc-gui](https://github.com/Redtropig/so-vits-svc-gui) project.
- Communicate/Interact with [so-vits-svc-gui](https://github.com/Redtropig/so-vits-svc-gui) via TCP protocol socket.

### **References**
- So-VITS-SVC Java Swing GUI Repo: [so-vits-svc-gui](https://github.com/Redtropig/so-vits-svc-gui)
- So-VITS-SVC main project repo: [so-vits-svc](https://github.com/svc-develop-team/so-vits-svc)
- Audio Slicer repo: [audio-slicer](https://github.com/openvpi/audio-slicer)
- [GUI-Icon.png](https://avatars.githubusercontent.com/u/127122328?s=400&u=5395a98a4f945a3a50cb0cc96c2747505d190dbc&v=4)
