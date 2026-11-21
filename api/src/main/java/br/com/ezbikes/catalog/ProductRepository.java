package br.com.ezbikes.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("""
            select p from Product p
            where (:category is null or p.category = :category)
              and (:brand is null or lower(p.brand) = lower(cast(:brand as string)))
            order by p.name
            """)
    List<Product> search(@Param("category") Category category, @Param("brand") String brand);
}
