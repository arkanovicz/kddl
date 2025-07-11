lexer grammar kddlLexer;

// keywords
DATABASE: 'database' ;
SCHEMA: 'schema' ;
TABLE: 'table' ;
UP: 'up' ;
DOWN: 'down' ;
LEFT: 'left' ;
RIGHT: 'right' ;
NULL: 'null' ;
TRUE: 'true' ;
FALSE: 'false' ;
BOOLEAN: 'boolean' ;
BIGINT: 'bigint' ;
INT: 'int' ( 'eger' )? ;
SMALLINT: 'smallint' ( 'eger' )? ;
SERIAL: 'serial' ;
LONG: 'long' ;
FLOAT: 'float' ;
DOUBLE: 'double' ;
MONEY: 'money' ;
NUMERIC: 'numeric' ;
TIME: 'time' ;
DATE: 'date' ;
DATETIME: 'datetime' | 'timestamp' ;
DATETIMETZ : 'datetimetz' | 'datetime_tz' | 'timestamptz' | 'timestamp_tz' ;
INTERVAL: 'interval' ;
CHAR: 'char' ;
VARCHAR: 'varchar' ;
TEXT: 'text' | 'clob' ;
BLOB: 'blob' ;
ENUM: 'enum' ;
UUID: 'uuid' ;
JSON: 'json' | 'jsonb' ;
CASCADE: 'cascade' ;

// values
LABEL: [a-zA-Z_][a-zA-Z0-9_]* ;
INTEGER: [0-9]+ ;
STRING: '\'' .*? '\'' ;

// symbols
// ARROW: '-'+ '>' ;
LC: '{' ;
RC: '}' ;
FS: ':' ;
LP: '(' ;
RP: ')' ;
DOT: '.' ;
LA: '<' ;
RA: '>' ;
ST: '*' ;
QM: '?' ;
PL: '+' ;
MN: '-' ;
EM: '!' ;
CM: ',' ;
EQ: '=' ;


// whitespaces and comments
WS: [ \t\n\r]+ -> skip ;
LINE_COMMENT : '//' .*? '\n' -> skip ;
BLOCK_COMMENT:'/*' .*? '*/' -> skip ;
