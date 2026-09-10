package com.fstojilj.luddite.sync.server.controller;

import com.fstojilj.luddite.sync.common.dto.ServerPortResponse;
import com.fstojilj.luddite.sync.server.service.FileSocketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API exposing server runtime info that clients can't otherwise discover, such as
 * the current socket-listening port, which can be changed live via the admin CLI's
 * {@code port} command.
 */
@RestController
@RequestMapping("/api/server")
@RequiredArgsConstructor
public class ServerInfoController {

    private final FileSocketService fileSocketService;

    /**
     * Returns the port the file-transfer socket is currently listening on.
     */
    @GetMapping("/socket-port")
    public ServerPortResponse socketPort() {
        return new ServerPortResponse(fileSocketService.getPort());
    }
}
