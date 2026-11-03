package com.storenode.application;

import com.storenode.application.port.KitchenTicketStore;
import com.storenode.domain.event.OrderPlaced;
import com.storenode.domain.kitchen.KitchenTicket;
import com.storenode.domain.shared.Ids;

import java.time.Clock;
import java.util.List;

/** The kitchen side: turns "an order was placed" into a ticket on the kitchen screen. */
public class KitchenService {

    private final KitchenTicketStore tickets;
    private final BranchSettings branch;
    private final Clock clock;

    public KitchenService(KitchenTicketStore tickets, BranchSettings branch, Clock clock) {
        this.tickets = tickets;
        this.branch = branch;
        this.clock = clock;
    }

    /** Safe to call twice for the same order: the store keeps only one ticket per order. */
    public void onOrderPlaced(OrderPlaced event) {
        tickets.createIfAbsent(KitchenTicket.open(event, Ids.newId(), branch.id(), clock.instant()));
    }

    public List<KitchenTicket> openTickets() {
        return tickets.findOpen();
    }
}
