public type Foo distinct isolated client object {
    isolated function execute() returns anydata|error;
};

public type Bar distinct isolated client object {
    isolated function execute() returns anydata|error;
};

public type Baz isolated client object {
    isolated function execute() returns anydata|error;
};

public type Xyz distinct isolated service object {
    isolated function execute() returns anydata|error;
};

public type Qux distinct isolated service object {
    isolated function execute() returns anydata|error;
};
