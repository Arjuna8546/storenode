package com.storenode.web;

import com.storenode.application.KitchenService;
import com.storenode.web.dto.KitchenTicketResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/kitchen")
public class KitchenController {

    private final KitchenService kitchen;

    public KitchenController(KitchenService kitchen) {
        this.kitchen = kitchen;
    }

    /** What the kitchen screen polls: tickets that are still open, oldest first. */
    @GetMapping("/tickets")
    public List<KitchenTicketResponse> openTickets() {
        return kitchen.openTickets().stream().map(KitchenTicketResponse::from).toList();
    }
}
