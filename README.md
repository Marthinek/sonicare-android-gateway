# Sonicare Condor v0.1.17 Subscribe-first

This version keeps the working Condor V4 handshake, paced BLE TX queue, early GATT 257 retry and discovery logic, but changes the Condor application flow:

GetProducts -> Subscribe 1/Sonicare, 1/RoutineStatus, 1/Battery, 1/BrushHead, 1/SessionStorage -> GetPorts/GetProps discovery.

No changes to themes.xml or backup/.
