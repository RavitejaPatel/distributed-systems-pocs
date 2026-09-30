package com.poc.inventory;

import com.poc.inventory.grpc.InventoryServiceGrpc;
import com.poc.inventory.grpc.ReserveRequest;
import com.poc.inventory.grpc.ReserveResponse;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

public class InventoryServiceApp {

    // In-memory stock: one item, starting quantity 5. No persistence yet.
    private static int stock = 5;

    public static void main(String[] args) throws Exception {
        int port = 50051;

        Server server = ServerBuilder.forPort(port)
                .addService(new InventoryServiceImpl())
                .build();

        server.start();
        System.out.println("InventoryService listening on port " + port + " (stock = " + stock + ")");

        server.awaitTermination();
    }

    // The actual RPC implementation — this is the "function" OrderService calls remotely.
    static class InventoryServiceImpl extends InventoryServiceGrpc.InventoryServiceImplBase {

        @Override
        public void checkAndReserve(ReserveRequest request, StreamObserver<ReserveResponse> responseObserver) {
            System.out.println("Received request: item=" + request.getItemId() + ", qty=" + request.getQuantity());

            ReserveResponse response;

            if (stock >= request.getQuantity()) {
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

            responseObserver.onNext(response);   // send the response back to the caller
            responseObserver.onCompleted();       // signal the RPC is done
        }
    }
}