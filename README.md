# fitpro-ftms-bridge

**fitpro-ftms-bridge** is an independent, open-source project created for the interoperability of S15i smart bikes with "open Bluetooth" FTMS (Fitness Machine Service) virtual ride applications, such as Zwift and MyWhoosh. It does this through a self-developed Android application ("the bridge app") that runs on the S15i's own built-in tablet and re-exposes the bike as an open Bluetooth FTMS device.

## Scope of This Project

This project exists to enable S15i smart bikes to interoperate with third-party virtual ride applications that implement the open, publicly documented Bluetooth FTMS specification — without an iFit account, iFit cloud service, or any other vendor service or subscription. In particular, the bridge app:

- Takes over, at the user's explicit instruction and in a fully reversible manner, the tablet's exclusive USB link to the bike's onboard electronics (the "brainboard"). The S15i's stock application is temporarily suspended while the bridge app is in use and is restored to its original, fully functional state at any time by the user;
- Speaks the vendor's own (unauthenticated and unencrypted) USB protocol directly to the brainboard, using protocol details identified through the project's independent reverse-engineering analysis;
- Re-exposes the bike over the tablet's own Bluetooth radio as a standard Bluetooth FTMS device (service `0x1826`), so that any phone, tablet, or PC running a conformant FTMS application (e.g., Zwift, MyWhoosh) can read the bike's telemetry (speed, cadence, power, heart rate, distance, resistance, and other parameters) and issue resistance, start/stop, and — where the console's capability includes it — incline commands. Data and control are served to bonded/authorized devices only;
- Makes no modification to the bike's firmware or electronics: no root access, no `/system` modification, no bootloader or flash access of any kind.

All code in this project is independently written. It does not contain, embed, reproduce, modify, or redistribute any firmware, software, or other work of any third party, and it does not circumvent any technological protection measure for the purpose of accessing, copying, or otherwise exploiting any copyrighted work.

This project does not provide any service to, or relationship with, the owners or operators of the S15i or of any virtual ride application, and it is not a substitute for any vendor-provided software, firmware, or accessory.

## Legal Notice

### Reverse Engineering and the DMCA

Development of this project required reverse engineering: the S15i's tablet controls the bike's onboard electronics over a custom, vendor-developed USB protocol (the "FitPro1" protocol). That protocol is unauthenticated and unencrypted; the project's authors identified and analyzed it through their own independent observation and analysis of the bike's tablet and its stock software. This analysis was undertaken solely to achieve interoperability between the S15i and the open, publicly documented Bluetooth FTMS specification.

With respect to Section 1201 of the Digital Millennium Copyright Act (17 U.S.C. § 1201), the project's authors set out the following description and position:

1. **The Section 1201(f) safe harbor.** To the extent that any element of the vendor's software, protocol, or firmware is, or could be characterized as, a technological protection measure, the project's authors believe that the reverse engineering and interoperability work performed for this project — conducted solely to achieve interoperability between the S15i and the open, publicly documented Bluetooth FTMS specification — falls within the safe harbor / exemption provided by Section 1201(f) of the DMCA, 17 U.S.C. § 1201(f), including the exemptions from the prohibitions of § 1201(a) promulgated by the Register of Copyrights under the triennial rulemaking process (see 37 C.F.R. § 201.40 et seq.), and, where applicable, within the doctrine of fair use under 17 U.S.C. § 107. The authors further note, as a matter of fact, that this project does not access, copy, display, perform, or otherwise exploit any copyrighted work of the vendor, and does not circumvent any measure for the purpose of accessing or copying any copyrighted work.

All code, documentation, and protocol mappings in this project were independently authored. The project does not extract, copy, reproduce, embed, or distribute any firmware, software, or other work or trade secret of any third party, and no portion of any third-party work is included in or derived from this project's source code or binaries.

### No Affiliation or Endorsement

fitpro-ftms-bridge is an independent, open-source project. It is **not** affiliated with, endorsed by, sponsored by, supervised by, or otherwise associated with the manufacturer of the S15i, the developer or publisher of iFit (the S15i's stock application and cloud service), the developers or publishers of Zwift, MyWhoosh, or any other application, platform, or product referenced in this project or its documentation, or with Bluetooth SIG or any standards body.

"S15i," "iFit," "Zwift," "MyWhoosh," "FTMS," and all other product names, trade names, logos, and trademarks referenced herein are the property of their respective owners. Such names and marks are used solely for identification, description, and interoperability purposes (nominative fair use) and do not imply any affiliation with, sponsorship of, or endorsement of this project by their respective owners. All rights not expressly granted are reserved by the respective owners.

### No Legal Advice

Nothing contained in this project, including this README, constitutes legal advice, nor does it create an attorney–client relationship with any person. The legal statements above are the project's own account of its basis of operation and are offered in good faith; they are not a legal opinion. Each user is solely responsible for determining the legality of their own acquisition, use, modification, and distribution of this project in their own jurisdiction, and is encouraged to consult their own legal counsel with respect to questions of law, including any questions under the DMCA or any other statute.

## Disclaimer — Use at Your Own Risk

THIS PROJECT, INCLUDING ALL CODE, DOCUMENTATION, AND MATERIALS, IS PROVIDED "AS IS," WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. THE AUTHORS AND COPYRIGHT HOLDERS MAKE NO REPRESENTATIONS OR WARRANTIES ABOUT THE ACCURACY, RELIABILITY, OR OPERABILITY OF THIS PROJECT AND DISCLAIM ALL LIABILITY ARISING FROM OR RELATED TO ITS USE. USE OF THIS PROJECT — INCLUDING THE USE OF, MODIFICATION OF, OR INTEROPERABILITY ENABLED BY IT — IS ENTIRELY AT YOUR OWN RISK, AND YOU, AND ONLY YOU, ARE RESPONSIBLE FOR ANY DAMAGE TO YOURSELF, YOUR PROPERTY, YOUR BICYCLE, OR ANY OTHER PERSON OR PROPERTY RESULTING FROM ITS USE, OR FOR ANY VIOLATION OF ANY LAW OR THIRD-PARTY RIGHT BY YOUR USE OF IT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES (INCLUDING BUT NOT LIMITED TO GENERAL, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES), OR OTHER LIABILITY, ARISING IN ANY WAY OUT OF THE USE OF THIS PROJECT, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## License

This project is licensed under the Apache License, Version 2.0. See the [LICENSE](./LICENSE) file for the full text.
