package br.com.ezbikes.catalog;

import br.com.ezbikes.common.NotFoundException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ProductResponse> list(@RequestParam(required = false) Category category,
                                      @RequestParam(required = false) String brand) {
        return products.search(category, brand).stream()
                .map(ProductResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ProductResponse get(@PathVariable Long id) {
        return products.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new NotFoundException("Produto %d não encontrado".formatted(id)));
    }
}
