-- ORDER_PAID: order.paid de GiftHub registra el cobro de la venta de canal en el POS y la cierra.
ALTER TABLE operations DROP CONSTRAINT operations_kind_check;
ALTER TABLE operations ADD CONSTRAINT operations_kind_check
    CHECK (kind IN ('ISSUE_INVOICE', 'CANCEL_INVOICE', 'CREATE_ORDER', 'ORDER_STATUS', 'ORDER_PAID', 'CATALOG_SYNC'));
