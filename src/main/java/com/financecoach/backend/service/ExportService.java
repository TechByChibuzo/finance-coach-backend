package com.financecoach.backend.service;

import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class ExportService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private S3Service s3Service;

    public String exportTransactionsCSV(UUID userId) {
        List<Transaction> transactions = transactionRepository.findByUserId(userId);

        StringBuilder csv = new StringBuilder();
        csv.append("Date,Merchant,Category,Amount,Currency\n");

        for (Transaction t : transactions) {
            csv.append(t.getDate()).append(",")
                    .append(escapeCsv(t.getMerchantName() != null ? t.getMerchantName() : t.getName()))
                    .append(",")
                    .append(escapeCsv(t.getCategory()))
                    .append(",")
                    .append(t.getAmount())
                    .append(",")
                    .append(t.getCurrencyCode() != null ? t.getCurrencyCode() : "USD")
                    .append("\n");
        }

        String key = "exports/" + userId + "/transactions-" +
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) +
                "-" + UUID.randomUUID() + ".csv";

        s3Service.uploadFile(key, csv.toString().getBytes(), "text/csv");
        return s3Service.generatePresignedUrl(key);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}