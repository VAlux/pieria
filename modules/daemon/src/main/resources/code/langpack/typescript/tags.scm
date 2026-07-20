; TypeScript definitions and relations. Kept separate from JavaScript because queries are compiled
; against each grammar and TypeScript contributes additional named node types.

(class_declaration name: (_) @def.name) @def.class
(abstract_class_declaration name: (_) @def.name) @def.class
(interface_declaration name: (_) @def.name) @def.interface
(enum_declaration name: (_) @def.name) @def.enum
(type_alias_declaration name: (_) @def.name) @def.type_alias

(function_declaration name: (identifier) @def.name) @def.function
(generator_function_declaration name: (identifier) @def.name) @def.function
(function_signature name: (identifier) @def.name) @def.function
(method_definition name: [(property_identifier) (private_property_identifier)] @def.name) @def.method
(method_signature name: (property_identifier) @def.name) @def.method
(abstract_method_signature name: (property_identifier) @def.name) @def.method
(public_field_definition name: [(property_identifier) (private_property_identifier)] @def.name) @def.field

(variable_declarator
  name: (identifier) @def.name
  value: [(arrow_function) (function_expression)]) @def.function
(variable_declarator name: (identifier) @def.name) @def.variable

(import_statement source: (string) @ref.name) @ref.import
(export_statement source: (string) @ref.name) @ref.export
(extends_clause value: (_) @ref.name) @ref.extends
(extends_type_clause (_) @ref.name) @ref.extends
(implements_clause (_) @ref.name) @ref.implements

(call_expression function: (identifier) @ref.name) @ref.call
(call_expression
  function: (member_expression property: (property_identifier) @ref.name)) @ref.call
