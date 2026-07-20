; JavaScript/JSX definitions and relations. Extractors supply file-module qualification and scope.

(class_declaration name: (_) @def.name) @def.class
(function_declaration name: (identifier) @def.name) @def.function
(generator_function_declaration name: (identifier) @def.name) @def.function
(method_definition name: [(property_identifier) (private_property_identifier)] @def.name) @def.method
(field_definition property: [(property_identifier) (private_property_identifier)] @def.name) @def.field

(variable_declarator
  name: (identifier) @def.name
  value: [(arrow_function) (function_expression)]) @def.function
(variable_declarator name: (identifier) @def.name) @def.variable

(import_statement source: (string) @ref.name) @ref.import
(export_statement source: (string) @ref.name) @ref.export
(class_heritage (_) @ref.name) @ref.extends

(call_expression function: (identifier) @ref.name) @ref.call
(call_expression
  function: (member_expression property: (property_identifier) @ref.name)) @ref.call
