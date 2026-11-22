package br.com.ezbikes.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Baixa o estoque de forma atômica: o UPDATE só afeta a linha se houver quantidade
     * suficiente, então dois checkouts concorrentes nunca vendem a mesma unidade.
     *
     * @return 1 se baixou, 0 se o estoque era insuficiente
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update products
               set stock = stock - :qty, version = version + 1
             where id = :id and stock >= :qty
            """, nativeQuery = true)
    int decrementStock(@Param("id") long id, @Param("qty") int qty);

    @Query("select p.stock from Product p where p.id = :id")
    int findStockById(@Param("id") long id);

    /** Devolve unidades ao estoque (por exemplo, quando o pagamento falha). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update products
               set stock = stock + :qty, version = version + 1
             where id = :id
            """, nativeQuery = true)
    int incrementStock(@Param("id") long id, @Param("qty") int qty);
}
