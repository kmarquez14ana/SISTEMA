-- Esquema de Sabor & Arte. Compatible con PostgreSQL y ejecuciones repetidas.
-- No se usa ON CONFLICT: las tablas existentes pueden no tener restricciones UNIQUE.

CREATE TABLE IF NOT EXISTS categorias (
    id_categoria SERIAL PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL UNIQUE,
    descripcion VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS productos (
    id_producto SERIAL PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL,
    descripcion VARCHAR(300),
    precio NUMERIC(10,2) NOT NULL CHECK (precio >= 0),
    stock INTEGER DEFAULT 0,
    id_categoria INTEGER REFERENCES categorias(id_categoria),
    imagen_url VARCHAR(255),
    estado VARCHAR(30) DEFAULT 'Disponible'
);

CREATE TABLE IF NOT EXISTS clientes (
    id_cliente SERIAL PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL,
    telefono VARCHAR(25),
    correo VARCHAR(120)
);

CREATE TABLE IF NOT EXISTS mesas (
    id_mesa SERIAL PRIMARY KEY,
    numero INTEGER NOT NULL UNIQUE,
    capacidad INTEGER NOT NULL DEFAULT 4,
    estado VARCHAR(30) NOT NULL DEFAULT 'Disponible'
);

CREATE TABLE IF NOT EXISTS reservas (
    id_reserva SERIAL PRIMARY KEY,
    id_cliente INTEGER NOT NULL REFERENCES clientes(id_cliente),
    id_mesa INTEGER NOT NULL REFERENCES mesas(id_mesa),
    fecha_reserva TIMESTAMP NOT NULL,
    numero_personas INTEGER NOT NULL,
    estado VARCHAR(30) DEFAULT 'Pendiente'
);

CREATE TABLE IF NOT EXISTS pedidos (
    id_pedido SERIAL PRIMARY KEY,
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    id_cliente INTEGER NOT NULL REFERENCES clientes(id_cliente),
    estado VARCHAR(30) DEFAULT 'Pendiente',
    total NUMERIC(10,2) DEFAULT 0
);

CREATE TABLE IF NOT EXISTS detalle_pedido (
    id_detalle SERIAL PRIMARY KEY,
    id_pedido INTEGER NOT NULL REFERENCES pedidos(id_pedido) ON DELETE CASCADE,
    id_producto INTEGER NOT NULL REFERENCES productos(id_producto),
    cantidad INTEGER NOT NULL,
    precio_unitario NUMERIC(10,2) NOT NULL,
    subtotal NUMERIC(10,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS pagos (
    id_pago SERIAL PRIMARY KEY,
    id_pedido INTEGER NOT NULL REFERENCES pedidos(id_pedido),
    fecha_pago TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    metodo_pago VARCHAR(50),
    monto NUMERIC(10,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS facturas (
    id_factura SERIAL PRIMARY KEY,
    id_pedido INTEGER NOT NULL REFERENCES pedidos(id_pedido),
    numero_factura VARCHAR(50) UNIQUE,
    fecha_emision TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    subtotal NUMERIC(10,2) DEFAULT 0,
    impuesto NUMERIC(10,2) DEFAULT 0,
    total NUMERIC(10,2) DEFAULT 0,
    estado VARCHAR(30) DEFAULT 'Pendiente'
);

-- Categorías: inserta únicamente las que todavía no existen.
INSERT INTO categorias(nombre, descripcion)
SELECT v.nombre, v.descripcion
FROM (VALUES
    ('Hamburguesas','Hamburguesas y combos'),
    ('Pizzas','Pizzas artesanales'),
    ('Comida típica','Comida tradicional salvadoreña'),
    ('Platos fuertes','Platos principales'),
    ('Ensaladas','Ensaladas frescas'),
    ('Pastas','Pastas y especialidades'),
    ('Bebidas','Bebidas frías y calientes'),
    ('Postres','Postres y dulces'),
    ('Acompañamientos','Complementos'),
    ('Sándwiches','Sándwiches variados')
) AS v(nombre, descripcion)
WHERE NOT EXISTS (
    SELECT 1 FROM categorias c WHERE LOWER(c.nombre)=LOWER(v.nombre)
);

-- Mesas: no duplica números ya registrados.
INSERT INTO mesas(numero, capacidad, estado)
SELECT v.numero, v.capacidad, v.estado
FROM (VALUES
    (1,2,'Disponible'), (2,2,'Disponible'), (3,4,'Disponible'),
    (4,4,'Disponible'), (5,6,'Disponible'), (6,8,'Disponible')
) AS v(numero, capacidad, estado)
WHERE NOT EXISTS (SELECT 1 FROM mesas m WHERE m.numero=v.numero);

-- Productos iniciales: solo se agregan si aún no existen por nombre.
INSERT INTO productos(nombre, descripcion, precio, stock, id_categoria, imagen_url)
SELECT v.nombre, v.descripcion, v.precio, 50, c.id_categoria, v.imagen
FROM (VALUES
    ('Hamburguesa clásica','Carne, queso, lechuga y tomate',5.50,'images/hamburguesa.svg','Hamburguesas'),
    ('Pizza artesanal','Pizza de queso y tomate',8.50,'images/pizza.svg','Pizzas'),
    ('Pupusas mixtas','Pupusas de queso y chicharrón',1.25,'images/pupusas.svg','Comida típica'),
    ('Pollo a la plancha','Pollo con guarnición',7.25,'images/pollo.svg','Platos fuertes'),
    ('Ensalada fresca','Vegetales frescos de temporada',4.50,'images/ensalada.svg','Ensaladas'),
    ('Pasta cremosa','Pasta en salsa cremosa',6.75,'images/pasta.svg','Pastas'),
    ('Limonada','Limonada natural',1.75,'images/bebida.svg','Bebidas'),
    ('Pastel de chocolate','Porción de pastel',3.25,'images/postre.svg','Postres'),
    ('Papas fritas','Papas doradas',2.00,'images/papas.svg','Acompañamientos'),
    ('Sándwich especial','Jamón, queso y vegetales',4.25,'images/sandwich.svg','Sándwiches')
) AS v(nombre, descripcion, precio, imagen, categoria)
JOIN (
    SELECT MIN(id_categoria) AS id_categoria, LOWER(nombre) AS nombre
    FROM categorias GROUP BY LOWER(nombre)
) c ON c.nombre=LOWER(v.categoria)
WHERE NOT EXISTS (SELECT 1 FROM productos p WHERE LOWER(p.nombre)=LOWER(v.nombre));
