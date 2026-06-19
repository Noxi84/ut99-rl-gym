1) Download umake
2) Maak file onder UT dir:
   NeuralNetWebserver\Classes\NeuralNetWebServer.uc
   Print hieron in json formaat de gewenste output. (unrealscript)

3) Maak file onder System folder: NeuralNetWebserver.int
   met inhoud:

[Public]
Preferences=(Caption="UT NeuralNet WebServer",Parent="Networking",Class=NeuralNetWebserver.NeuralNetWebserver)

[WebServer]
ClassCaption="UT NeuralNet WebServer"

4) Run umake.
Eg:
wine ~/projects/ut99/Umake.exe make

5) Edit unrealtournament.ini

Voeg de volgende regels toe (of editeer):
[UWeb.WebServer]
;Applications[0]=UTServerAdmin.UTServerAdmin
;ApplicationPaths[0]=/ServerAdmin
;Applications[1]=UTServerAdmin.UTImageServer
;ApplicationPaths[1]=/images
Applications[0]=NeuralNetWebserver.NeuralNetWebserver
ApplicationPaths[0]=/utneuralnet
DefaultApplication=0
bEnabled=True
Applications[1]=
Applications[2]=
Applications[3]=
Applications[4]=
Applications[5]=
Applications[6]=
Applications[7]=
Applications[8]=
Applications[9]=
ApplicationPaths[1]=
ApplicationPaths[2]=
ApplicationPaths[3]=
ApplicationPaths[4]=
ApplicationPaths[5]=
ApplicationPaths[6]=
ApplicationPaths[7]=
ApplicationPaths[8]=
ApplicationPaths[9]=
ListenPort=8080
MaxConnections=30
ServerName=

6) Start UT in multiplayer mode
7) Open 127.0.0.1:8080/utneuralnet