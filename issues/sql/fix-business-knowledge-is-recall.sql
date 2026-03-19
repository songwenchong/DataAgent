UPDATE business_knowledge
SET is_recall = 1,
    updated_time = NOW()
WHERE is_deleted = 0
  AND is_recall = 0;
