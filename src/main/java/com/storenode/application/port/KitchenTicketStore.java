package com.storenode.application.port;

import com.storenode.domain.kitchen.KitchenTicket;

import java.util.List;

public interface KitchenTicketStore {

    /** Saves the ticket unless one already exists for the same order, so calling it twice is harmless. */
    void createIfAbsent(KitchenTicket ticket);

    List<KitchenTicket> findOpen();
}
