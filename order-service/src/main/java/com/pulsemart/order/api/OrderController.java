package com.pulsemart.order.api;

import com.pulsemart.order.api.dto.OrderResponse;
import com.pulsemart.order.api.dto.PlaceOrderRequest;
import com.pulsemart.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        return OrderResponse.from(orderService.placeOrder(request));
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@PathVariable UUID orderId) {
        return OrderResponse.from(orderService.getOrder(orderId));
    }

    @GetMapping
    public List<OrderResponse> getOrdersByCustomer(@RequestParam UUID customerId) {
        return orderService.getOrdersByCustomer(customerId).stream()
                .map(OrderResponse::from)
                .toList();
    }
}
