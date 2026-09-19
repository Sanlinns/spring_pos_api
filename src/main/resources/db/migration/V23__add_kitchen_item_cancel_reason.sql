alter table kitchen_ticket_items
    add column if not exists cancel_reason varchar(255);
