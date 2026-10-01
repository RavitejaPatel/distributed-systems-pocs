package com.poc.inventory;

import com.poc.inventory.grpc.InventoryServiceGrpc;
import com.poc.inventory.grpc.ReserveRequest;
import com.poc.inventory.grpc.ReserveResponse;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryServiceApp {

    private static int stock = 1;

    // Remembers which idempotency keys we've already processed, and what we
    // responded with — so a retried request returns the ORIGINAL result
    // instead of re-running the reservation logic a second time.
    private static final Map<String, ReserveResponse> processedRequests = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int port = 50051;

        Server server = ServerBuilder.forPort(port)
                .addService(new InventoryServiceImpl())
                .build();

        server.start();
        System.out.println("InventoryService listening on port " + port + " (stock = " + stock + ")");

        server.awaitTermination();
    }

    static class InventoryServiceImpl extends InventoryServiceGrpc.InventoryServiceImplBase {

        @Override
        public void checkAndReserve(ReserveRequest request, StreamObserver<ReserveResponse> responseObserver) {
            System.out.println("Received request: item=" + request.getItemId()
                    + ", qty=" + request.getQuantity()
                    + ", idempotencyKey=" + request.getIdempotencyKey());

            String key = request.getIdempotencyKey();

            // Check BEFORE doing anything else: have we already handled this exact
            // logical request? If so, return the stored result — do NOT touch stock again.
            if (!key.isEmpty() && processedRequests.containsKey(key)) {
                System.out.println("Duplicate request detected for key=" + key + " — returning cached response, stock untouched");
                ReserveResponse cached = processedRequests.get(key);
                responseObserver.onNext(cached);
                responseObserver.onCompleted();
                return;
            }

            if (request.getItemId().equals("item-slow")) {
                try {
                    System.out.println("Simulating a slow dependency... sleeping 10 seconds");
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            ReserveResponse response;

            synchronized (InventoryServiceApp.class) {
                if (stock >= request.getQuantity()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    stock -= request.getQuantity();
                    response = ReserveResponse.newBuilder()
                            .setSuccess(true)
                            .setMessage("Reserved successfully")
                            .setRemainingStock(stock)
                            .build();
                } else {
                    response = ReserveResponse.newBuilder()
                            .setSuccess(false)
                            .setMessage("Insufficient stock")
                            .setRemainingStock(stock)
                            .build();
                }
            }

            // Remember this result so a retry with the same key returns it instead
            // of running the reservation logic again.
            if (!key.isEmpty()) {
                processedRequests.put(key, response);
            }

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}