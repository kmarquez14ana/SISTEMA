package sv.edu.restaurante;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class RestauranteController {
    private final JdbcTemplate jdbc;

    public RestauranteController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        return Map.of("estado", "ok", "aplicacion", "Sabor & Arte");
    }

    @GetMapping("/categorias")
    public List<Map<String, Object>> categorias() {
        return jdbc.queryForList("SELECT id_categoria, nombre, descripcion FROM categorias ORDER BY nombre");
    }

    @GetMapping("/menu")
    public List<Map<String, Object>> menu(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "todos") String categoria) {
        String texto = "%" + q.trim().toLowerCase() + "%";
        boolean todas = categoria == null || categoria.isBlank() || categoria.equalsIgnoreCase("todos");
        return jdbc.queryForList("""
            SELECT p.id_producto, p.nombre, p.descripcion, p.precio, p.stock,
                   p.imagen_url, p.estado, c.nombre AS categoria,
                   c.id_categoria
            FROM productos p
            LEFT JOIN categorias c ON c.id_categoria=p.id_categoria
            WHERE (LOWER(p.nombre) LIKE ? OR LOWER(COALESCE(p.descripcion,'')) LIKE ?)
              AND (? = TRUE OR LOWER(COALESCE(c.nombre,'')) = LOWER(?))
              AND LOWER(COALESCE(p.estado,'Disponible')) = 'disponible'
            ORDER BY p.nombre
            """, texto, texto, todas, categoria);
    }

    @GetMapping("/mesas")
    public List<Map<String, Object>> mesas() {
        return jdbc.queryForList("SELECT id_mesa, numero, capacidad, estado FROM mesas ORDER BY numero");
    }

    @PostMapping("/reservas")
    @Transactional
    public Map<String, Object> reservar(@RequestBody Map<String, Object> data) {
        String nombre = required(data, "nombre");
        String telefono = String.valueOf(data.getOrDefault("telefono", ""));
        String correo = String.valueOf(data.getOrDefault("correo", ""));
        int mesa = Integer.parseInt(String.valueOf(data.get("id_mesa")));
        int personas = Integer.parseInt(String.valueOf(data.get("numero_personas")));
        LocalDateTime fecha = LocalDateTime.parse(String.valueOf(data.get("fecha_reserva")));
        Integer clienteId = jdbc.queryForObject(
            "INSERT INTO clientes(nombre, telefono, correo) VALUES (?,?,?) RETURNING id_cliente",
            Integer.class, nombre, telefono, correo);
        Integer reservaId = jdbc.queryForObject("""
            INSERT INTO reservas(id_cliente,id_mesa,fecha_reserva,numero_personas,estado)
            VALUES (?,?,?,?, 'Pendiente') RETURNING id_reserva
            """, Integer.class, clienteId, mesa, Timestamp.valueOf(fecha), personas);
        return Map.of("mensaje", "Reserva registrada", "id_reserva", reservaId);
    }

    @PostMapping("/pedidos")
    @Transactional
    public Map<String, Object> pedido(@RequestBody Map<String, Object> data) {
        String nombre = required(data, "nombre");
        String telefono = String.valueOf(data.getOrDefault("telefono", ""));
        String correo = String.valueOf(data.getOrDefault("correo", ""));
        Object rawItems = data.get("items");
        if (!(rawItems instanceof List<?> items) || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El pedido no contiene productos");
        }
        Integer clienteId = jdbc.queryForObject(
            "INSERT INTO clientes(nombre,telefono,correo) VALUES (?,?,?) RETURNING id_cliente",
            Integer.class, nombre, telefono, correo);
        Integer pedidoId = jdbc.queryForObject(
            "INSERT INTO pedidos(id_cliente,estado,total) VALUES (?, 'Pendiente', 0) RETURNING id_pedido",
            Integer.class, clienteId);
        BigDecimal total = BigDecimal.ZERO;
        for (Object itemObj : items) {
            if (!(itemObj instanceof Map<?, ?> item)) continue;
            int id = Integer.parseInt(String.valueOf(item.get("id_producto")));
            int cantidad = Integer.parseInt(String.valueOf(item.get("cantidad")));
            if (cantidad < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cantidad inválida");
            Map<String, Object> producto = jdbc.queryForMap(
                "SELECT precio FROM productos WHERE id_producto=? AND LOWER(estado)='disponible'", id);
            BigDecimal precio = new BigDecimal(String.valueOf(producto.get("precio")));
            BigDecimal subtotal = precio.multiply(BigDecimal.valueOf(cantidad));
            jdbc.update("""
                INSERT INTO detalle_pedido(id_pedido,id_producto,cantidad,precio_unitario,subtotal)
                VALUES (?,?,?,?,?)
                """, pedidoId, id, cantidad, precio, subtotal);
            total = total.add(subtotal);
        }
        jdbc.update("UPDATE pedidos SET total=? WHERE id_pedido=?", total, pedidoId);
        return Map.of("mensaje", "Pedido registrado", "id_pedido", pedidoId, "total", total);
    }

    @GetMapping("/pedidos")
    public List<Map<String, Object>> pedidos() {
        return jdbc.queryForList("""
            SELECT p.id_pedido,p.fecha,p.estado,p.total,c.nombre AS cliente
            FROM pedidos p JOIN clientes c ON c.id_cliente=p.id_cliente
            ORDER BY p.fecha DESC
            """);
    }

    @PostMapping("/pagos")
    @Transactional
    public Map<String, Object> pagar(@RequestBody Map<String, Object> data) {
        int pedidoId = Integer.parseInt(String.valueOf(data.get("id_pedido")));
        String metodo = required(data, "metodo_pago");
        BigDecimal monto = new BigDecimal(String.valueOf(data.get("monto")));
        BigDecimal total = jdbc.queryForObject("SELECT total FROM pedidos WHERE id_pedido=?", BigDecimal.class, pedidoId);
        if (monto.compareTo(total) < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El monto es menor al total");
        Integer pagoId = jdbc.queryForObject("""
            INSERT INTO pagos(id_pedido,metodo_pago,monto) VALUES (?,?,?) RETURNING id_pago
            """, Integer.class, pedidoId, metodo, monto);
        jdbc.update("UPDATE pedidos SET estado='Pagado' WHERE id_pedido=?", pedidoId);
        return Map.of("mensaje", "Pago registrado", "id_pago", pagoId);
    }

    @PostMapping("/facturas/{pedidoId}")
    @Transactional
    public Map<String, Object> facturar(@PathVariable int pedidoId) {
        Map<String, Object> pedido = jdbc.queryForMap("SELECT total FROM pedidos WHERE id_pedido=?", pedidoId);
        BigDecimal total = new BigDecimal(String.valueOf(pedido.get("total")));
        BigDecimal subtotal = total.divide(new BigDecimal("1.13"), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal impuesto = total.subtract(subtotal);
        String numero = "SA-" + System.currentTimeMillis();
        Integer id = jdbc.queryForObject("""
            INSERT INTO facturas(id_pedido,numero_factura,subtotal,impuesto,total,estado)
            VALUES (?,?,?,?,?,'Emitida') RETURNING id_factura
            """, Integer.class, pedidoId, numero, subtotal, impuesto, total);
        return Map.of("mensaje", "Factura generada", "id_factura", id, "numero_factura", numero, "total", total);
    }

    @GetMapping("/facturas")
    public List<Map<String, Object>> facturas() {
        return jdbc.queryForList("""
            SELECT f.id_factura,f.numero_factura,f.fecha_emision,f.subtotal,f.impuesto,f.total,f.estado,
                   p.id_pedido,c.nombre AS cliente
            FROM facturas f JOIN pedidos p ON p.id_pedido=f.id_pedido
            JOIN clientes c ON c.id_cliente=p.id_cliente
            ORDER BY f.fecha_emision DESC
            """);
    }

    private String required(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el campo: " + key);
        }
        return value.toString().trim();
    }
}
