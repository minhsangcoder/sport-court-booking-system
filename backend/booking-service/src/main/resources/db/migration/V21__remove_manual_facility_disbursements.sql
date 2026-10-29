-- The facility manual payout/refund workflow is removed. Obligations, receipts,
-- settlements and refund entitlements stay as historical evidence for reports.
DROP TRIGGER facility_receipt_binding ON facility_disbursement_receipts;
DROP FUNCTION validate_facility_receipt();
ALTER TABLE facility_disbursement_receipts DROP CONSTRAINT facility_disbursement_receipts_operation_id_fkey;
DROP TABLE facility_finance_audit;
DROP TABLE facility_finance_commands;
DROP TABLE facility_disbursement_operations;
DROP SEQUENCE facility_disbursement_codes;
