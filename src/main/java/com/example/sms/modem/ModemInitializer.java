package com.example.sms.modem;

import com.example.sms.serial.AtCommandClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends the standard sequence of initialisation AT commands to put the modem
 * into a known, ready state.
 */
@Component
@RequiredArgsConstructor
public class ModemInitializer {

    private static final Logger log = LoggerFactory.getLogger(ModemInitializer.class);

    private final AtCommandClient atClient;

    /**
     * Runs the full initialisation sequence.  Throws on any modem error or
     * timeout so the application fails fast if the modem is not responding
     * correctly.
     */
    public void initialize() {
        log.info("Initialising modem...");

        send("AT");                        // basic comms check
        send("ATE0");                      // disable command echo
        send("AT+CMGF=1");                 // text mode
        send("AT+CSCS=\"GSM\"");           // GSM character set
        send("AT+CNMI=2,1,0,0,0");        // new SMS notification via +CMTI
        send("AT+CPIN?");                  // SIM card status
        send("AT+CSQ");                    // signal quality

        log.info("Modem initialised successfully. Waiting for incoming SMS...");
    }

    private void send(String command) {
        String response = atClient.sendAndWait(command);
        log.debug("CMD={} => {}", command, response.trim());
    }
}
