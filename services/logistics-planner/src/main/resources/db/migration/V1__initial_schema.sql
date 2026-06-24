CREATE TABLE locations (
    id UUID PRIMARY KEY,
    code VARCHAR(100) UNIQUE NOT NULL,
    address TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    working_hours_start TIME,
    working_hours_end TIME,
    service_time_minutes INTEGER DEFAULT 0,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE vehicles (
    id UUID PRIMARY KEY,
    max_weight_kg DOUBLE PRECISION NOT NULL,
    max_volume_m3 DOUBLE PRECISION NOT NULL,
    depot_location_id UUID REFERENCES locations(id),
    shift_start TIME NOT NULL,
    shift_end TIME NOT NULL,
    active BOOLEAN DEFAULT TRUE
);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    pickup_location_id UUID REFERENCES locations(id),
    delivery_location_id UUID REFERENCES locations(id),
    delivery_window_start TIMESTAMP,
    delivery_window_end TIMESTAMP,
    weight_kg DOUBLE PRECISION NOT NULL,
    volume_m3 DOUBLE PRECISION NOT NULL,
    delay_buffer_percent DOUBLE PRECISION DEFAULT 0.0,
    plan_date DATE,
    status VARCHAR(50),
    created_at TIMESTAMP
);

CREATE TABLE plans (
    id UUID PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    plan_date DATE,
    generated_at TIMESTAMP,
    created_at TIMESTAMP
);

CREATE TABLE routes (
    id UUID PRIMARY KEY,
    plan_id UUID REFERENCES plans(id),
    vehicle_id UUID REFERENCES vehicles(id),
    frozen BOOLEAN DEFAULT FALSE,
    position INTEGER NOT NULL
);

CREATE TABLE stops (
    id UUID PRIMARY KEY,
    route_id UUID REFERENCES routes(id),
    location_id UUID REFERENCES locations(id),
    order_id UUID REFERENCES orders(id),
    position INTEGER NOT NULL,
    action VARCHAR(20) NOT NULL,
    arrival_time TIMESTAMP,
    departure_time TIMESTAMP
);

CREATE TABLE plan_unassigned_orders (
    plan_id UUID REFERENCES plans(id),
    order_id UUID REFERENCES orders(id),
    PRIMARY KEY (plan_id, order_id)
);
